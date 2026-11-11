package com.reddis.storage;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The in-memory storage: key -> typed value (string, list, hash, set, sorted set), with optional expiry,
 * memory accounting and the metadata eviction policies need.
 *
 * THREAD SAFETY
 *  - Every method is atomic for the single key it touches. Strings are immutable byte arrays; collections are
 *    mutable and only touched inside access(), which runs under ConcurrentHashMap.compute().
 *  - forEachEntry() hands out the live collection objects: only use it while no other thread mutates the store.
 *  - Access metadata (last access, LFU counter) is updated without locks: it is a statistic, races are harmless.
 *
 * BOOKKEEPING (one rule): every change to the map goes through put/remove/removeIfSame or a compute lambda that
 * calls track(), which keeps usedMemory, the all-keys index and the keys-with-deadline index in step.
 */
public class DataStore {

    /** ttlMillis() result for a key that does not exist (or has expired). */
    public static final long TTL_MISSING = -2;
    /** ttlMillis() result for a key that exists but has no timeout. */
    public static final long TTL_NO_EXPIRY = -1;
    /** The "no deadline" marker used by expireAtMillis(), restore(), forEachEntry() and KeySample. */
    public static final long NO_EXPIRY = -1;

    // LFU counter parameters (the Redis defaults)
    private static final int LFU_INIT = 5;
    private static final int LFU_MAX = 255;
    private static final int LFU_LOG_FACTOR = 10;
    private static final long LFU_DECAY_MILLIS = 60_000; // the counter drops by 1 per minute without access

    /** Receives every live entry during forEachEntry(). */
    @FunctionalInterface
    public interface EntryVisitor {
        void visit(byte[] key, RedisValue value, long expireAtMillis) throws IOException;
    }

    /**
     * (value, deadline) are immutable; the access metadata is the only mutable part. Deliberately does NOT override
     * equals(): conditional removal relies on identity comparison.
     */
    private static final class Entry {
        final RedisValue value;
        final long expireAtMillis; // absolute wall-clock time, or NO_EXPIRY

        volatile long lastAccessMillis;
        volatile long lastDecayMillis;
        volatile int frequency;

        Entry(RedisValue value, long expireAtMillis, long nowMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
            this.lastAccessMillis = nowMillis;
            this.lastDecayMillis = nowMillis;
            this.frequency = LFU_INIT;
        }

        /** A replacement entry (new value or deadline) that keeps the access history of the old one. */
        Entry(RedisValue value, long expireAtMillis, Entry previous) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
            this.lastAccessMillis = previous.lastAccessMillis;
            this.lastDecayMillis = previous.lastDecayMillis;
            this.frequency = previous.frequency;
        }

        boolean isExpiredAt(long nowMillis) {
            return expireAtMillis != NO_EXPIRY && nowMillis >= expireAtMillis;
        }

        /** Records an access: refreshes the LRU time and bumps the LFU counter (probabilistically, logarithmically). */
        void touch(long nowMillis, Random random) {
            lastAccessMillis = nowMillis;
            int counter = decayedFrequency(nowMillis);
            if (counter < LFU_MAX) {
                double base = Math.max(0, counter - LFU_INIT);
                double probability = 1.0 / (base * LFU_LOG_FACTOR + 1);
                if (random.nextDouble() < probability) {
                    counter++;
                }
            }
            frequency = counter;
            lastDecayMillis = nowMillis;
        }

        /** The LFU counter after forgetting one point per idle minute (does not modify the entry). */
        int decayedFrequency(long nowMillis) {
            long periods = Math.max(0, (nowMillis - lastDecayMillis) / LFU_DECAY_MILLIS);
            return (int) Math.max(0, frequency - periods);
        }
    }

    /** "Key X should be checked at time T". Ordered by due time inside the DelayQueue. */
    private final class ExpiryItem implements Delayed {
        final ByteArrayKey key;
        final long dueMillis;

        ExpiryItem(ByteArrayKey key, long dueMillis) {
            this.key = key;
            this.dueMillis = dueMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueMillis - clock.millis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(dueMillis, ((ExpiryItem) other).dueMillis);
        }
    }

    /** A tiny typed box to carry a result out of a lambda. */
    private static final class Result<R> {
        R value;
    }

    private final ConcurrentMap<ByteArrayKey, Entry> data = new ConcurrentHashMap<>();
    private final DelayQueue<ExpiryItem> expiryQueue = new DelayQueue<>();
    private final RandomKeySet allKeys = new RandomKeySet();
    private final RandomKeySet volatileKeys = new RandomKeySet(); // keys that have a deadline
    private final AtomicLong usedMemory = new AtomicLong();
    private final Clock clock;
    private final Random random;

    public DataStore() {
        this(Clock.systemUTC());
    }

    /** The clock is injected so tests can control time. */
    public DataStore(Clock clock) {
        this(clock, new Random());
    }

    /** The random source (used for the probabilistic LFU counter) is injectable so tests are reproducible. */
    public DataStore(Clock clock, Random random) {
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    // --------------------------------------------------------------- strings

    /** Stores a string with no timeout, replacing any existing value of ANY type and any timeout. */
    public void set(byte[] key, byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        put(new ByteArrayKey(key), new Entry(new StringValue(value), NO_EXPIRY, clock.millis()));
    }

    /** Stores a string, expiring ttlMillis from now. Replaces any existing value and timeout. */
    public void set(byte[] key, byte[] value, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        Objects.requireNonNull(value, "value must not be null");
        ByteArrayKey k = new ByteArrayKey(key);
        long now = clock.millis();
        long expireAt = expireAtFor(ttlMillis, now);
        put(k, new Entry(new StringValue(value), expireAt, now));
        expiryQueue.add(new ExpiryItem(k, expireAt));
    }

    /**
     * @return the string value, or null if the key does not exist or has expired
     * @throws WrongTypeException if the key holds something other than a string
     */
    public byte[] get(byte[] key) {
        Entry entry = live(new ByteArrayKey(key), true);
        if (entry == null) {
            return null;
        }
        if (entry.value instanceof StringValue string) {
            return string.bytes();
        }
        throw new WrongTypeException();
    }

    // ----------------------------------------------------------- collections

    /**
     * Runs {@code action} on the collection stored at {@code key}, atomically for that key.
     *
     *  - Key missing or expired: {@code factory} builds a fresh empty collection that the action receives.
     *    If the action leaves it non-empty it is stored (without timeout); if it stays empty nothing is stored.
     *  - Key holds a different type: throws WrongTypeException and nothing changes.
     *  - After the action, a collection that became empty is removed (Redis never keeps empty collections).
     *  - An existing key keeps its timeout. The key counts as accessed.
     *
     * The action runs while the key is locked: keep it short, never call this store from inside it, and
     * never let a reference to the collection escape (return copies or primitives).
     */
    public <V extends RedisValue, R> R access(byte[] key, Class<V> type, Supplier<V> factory, Function<V, R> action) {
        ByteArrayKey k = new ByteArrayKey(key);
        long now = clock.millis();
        Result<R> result = new Result<>();

        data.compute(k, (ignored, existing) -> {
            boolean live = existing != null && !existing.isExpiredAt(now);
            V collection;
            if (!live) {
                collection = factory.get();
            } else if (type.isInstance(existing.value)) {
                collection = type.cast(existing.value);
            } else {
                throw new WrongTypeException(); // compute() rethrows and leaves the mapping untouched
            }

            long oldSize = existing == null ? 0 : sizeOf(k, existing); // measured BEFORE the action changes it
            if (live) {
                existing.touch(now, random);
            }
            result.value = action.apply(collection);

            Entry updated;
            if (collection.isEmpty()) {
                updated = null;                                // delete the key (or never create it)
            } else if (live) {
                updated = existing;                            // same entry, changed contents
            } else {
                updated = new Entry(collection, NO_EXPIRY, now);
            }
            track(k, existing, oldSize, updated, updated == null ? 0 : sizeOf(k, updated));
            return updated;
        });

        return result.value;
    }

    /** @return "string", "list", "hash", "set", "zset" or "none" if the key does not exist. Does not count as an access. */
    public String type(byte[] key) {
        Entry entry = live(new ByteArrayKey(key), false);
        return entry == null ? "none" : entry.value.typeName();
    }

    // ------------------------------------------------- expiry (any value type)

    /**
     * Sets a timeout on an existing, live key of any type. A zero or negative ttl deletes the key immediately.
     *
     * @return true if the key existed (and was updated or deleted), false if it was missing/expired
     */
    public boolean expire(byte[] key, long ttlMillis) {
        long now = clock.millis();
        return applyDeadline(new ByteArrayKey(key), expireAtFor(ttlMillis, now), now);
    }

    /**
     * Sets an ABSOLUTE deadline (epoch milliseconds) on an existing, live key. A deadline that is not in the
     * future deletes the key immediately. This is what the AOF and snapshots replay.
     *
     * @return true if the key existed (and was updated or deleted), false if it was missing/expired
     */
    public boolean expireAt(byte[] key, long expireAtMillis) {
        return applyDeadline(new ByteArrayKey(key), expireAtMillis, clock.millis());
    }

    private boolean applyDeadline(ByteArrayKey k, long expireAt, long now) {
        boolean[] applied = {false};
        boolean[] scheduled = {false};

        data.computeIfPresent(k, (ignored, entry) -> {
            if (entry.isExpiredAt(now)) {
                track(k, entry, null);
                return null;                              // already dead: clean it up, report "missing"
            }
            applied[0] = true;
            if (expireAt <= now) {
                track(k, entry, null);
                return null;                              // deadline not in the future: delete now
            }
            scheduled[0] = true;
            Entry updated = new Entry(entry.value, expireAt, entry); // same value and access history
            track(k, entry, updated);
            return updated;
        });

        if (scheduled[0]) {
            expiryQueue.add(new ExpiryItem(k, expireAt));
        }
        return applied[0];
    }

    /** Removes the timeout from a live key. @return true only if a timeout was actually removed */
    public boolean persist(byte[] key) {
        ByteArrayKey k = new ByteArrayKey(key);
        long now = clock.millis();
        boolean[] removed = {false};
        data.computeIfPresent(k, (ignored, entry) -> {
            if (entry.isExpiredAt(now)) {
                track(k, entry, null);
                return null;
            }
            if (entry.expireAtMillis == NO_EXPIRY) {
                return entry;
            }
            removed[0] = true;
            Entry updated = new Entry(entry.value, NO_EXPIRY, entry);
            track(k, entry, updated);
            return updated;
        });
        return removed[0];
    }

    /** @return true if the key was live and has been removed (an expired key counts as already gone) */
    public boolean delete(byte[] key) {
        Entry old = remove(new ByteArrayKey(key));
        return old != null && !old.isExpiredAt(clock.millis());
    }

    /** Counts as an access (like Redis). */
    public boolean exists(byte[] key) {
        return live(new ByteArrayKey(key), true) != null;
    }

    /**
     * @return TTL_MISSING (-2) if there is no live key, TTL_NO_EXPIRY (-1) if it has no timeout,
     *         otherwise the milliseconds remaining (never negative). Does not count as an access.
     */
    public long ttlMillis(byte[] key) {
        Entry entry = live(new ByteArrayKey(key), false);
        if (entry == null) {
            return TTL_MISSING;
        }
        if (entry.expireAtMillis == NO_EXPIRY) {
            return TTL_NO_EXPIRY;
        }
        return Math.max(0, entry.expireAtMillis - clock.millis());
    }

    /**
     * @return TTL_MISSING (-2) if there is no live key, NO_EXPIRY (-1) if it has no timeout,
     *         otherwise the ABSOLUTE deadline in epoch milliseconds. Does not count as an access.
     */
    public long expireAtMillis(byte[] key) {
        Entry entry = live(new ByteArrayKey(key), false);
        return entry == null ? TTL_MISSING : entry.expireAtMillis;
    }

    /** Number of stored keys, INCLUDING expired ones not yet purged. */
    public int size() {
        return data.size();
    }

    // ----------------------------------------------------- memory and eviction

    /** The running estimate of memory used by all entries (expired-but-not-yet-removed ones included). */
    public long usedMemory() {
        return usedMemory.get();
    }

    /** Estimated bytes of one key with its value, or -1 if it does not exist. Does not count as an access. */
    public long memoryUsage(byte[] key) {
        ByteArrayKey k = new ByteArrayKey(key);
        Entry entry = live(k, false);
        return entry == null ? -1 : sizeOf(k, entry);
    }

    /** The access metadata of a key WITHOUT touching it, or null if it does not exist. */
    public KeySample inspect(byte[] key) {
        ByteArrayKey k = new ByteArrayKey(key);
        Entry entry = live(k, false);
        return entry == null ? null : describe(k, entry, clock.millis());
    }

    /**
     * Picks up to {@code count} distinct random keys (from the keys that have a deadline if volatileOnly) and
     * reports their access metadata. This is the "sample" in approximated LRU/LFU: O(count), independent of the
     * number of keys.
     */
    public List<KeySample> sample(int count, boolean volatileOnly, Random random) {
        RandomKeySet pool = volatileOnly ? volatileKeys : allKeys;
        long now = clock.millis();
        List<KeySample> samples = new ArrayList<>(count);
        for (ByteArrayKey k : pool.sample(count, random)) {
            Entry entry = data.get(k);
            if (entry != null) {
                samples.add(describe(k, entry, now));
            }
        }
        return samples;
    }

    /** Removes a key for eviction. @return true if it existed */
    public boolean evict(byte[] key) {
        return remove(new ByteArrayKey(key)) != null;
    }

    /** Recounts every entry from scratch (O(n)). The running total must always equal this. */
    public long recomputeUsedMemory() {
        long total = 0;
        for (Map.Entry<ByteArrayKey, Entry> mapEntry : data.entrySet()) {
            total += sizeOf(mapEntry.getKey(), mapEntry.getValue());
        }
        return total;
    }

    /**
     * Checks that both key indexes and the memory counter agree with the map, throwing IllegalStateException
     * otherwise. For tests and diagnostics: call it only while no other thread mutates the store.
     */
    public void validate() {
        Set<ByteArrayKey> keysInMap = new HashSet<>(data.keySet());
        Set<ByteArrayKey> volatileInMap = new HashSet<>();
        for (Map.Entry<ByteArrayKey, Entry> mapEntry : data.entrySet()) {
            if (mapEntry.getValue().expireAtMillis != NO_EXPIRY) {
                volatileInMap.add(mapEntry.getKey());
            }
        }
        if (!keysInMap.equals(allKeys.snapshot())) {
            throw new IllegalStateException("the all-keys index does not match the stored keys");
        }
        if (!volatileInMap.equals(volatileKeys.snapshot())) {
            throw new IllegalStateException("the volatile-keys index does not match the keys that have a deadline");
        }
        long recount = recomputeUsedMemory();
        if (recount != usedMemory.get()) {
            throw new IllegalStateException("used memory counter is " + usedMemory.get() + " but entries add up to " + recount);
        }
    }

    // ------------------------------------------------------------ persistence

    /**
     * Visits every LIVE entry (expired ones are skipped) with its absolute deadline or NO_EXPIRY.
     * The value is the real stored object: read it, never keep or modify it. Only call this while no other
     * thread mutates the store.
     */
    public void forEachEntry(EntryVisitor visitor) throws IOException {
        long now = clock.millis();
        for (Map.Entry<ByteArrayKey, Entry> mapEntry : data.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (entry.isExpiredAt(now)) {
                continue;
            }
            visitor.visit(mapEntry.getKey().bytes(), entry.value, entry.expireAtMillis);
        }
    }

    /**
     * Inserts a value loaded from disk, with its absolute deadline (or NO_EXPIRY). Entries that are already
     * expired and empty collections are ignored. The store takes ownership of the value object.
     */
    public void restore(byte[] key, RedisValue value, long expireAtMillis) {
        if (value.isEmpty()) {
            return;
        }
        long now = clock.millis();
        if (expireAtMillis != NO_EXPIRY && now >= expireAtMillis) {
            return; // never resurrect something that has already expired
        }
        ByteArrayKey k = new ByteArrayKey(key);
        put(k, new Entry(value, expireAtMillis, now));
        if (expireAtMillis != NO_EXPIRY) {
            expiryQueue.add(new ExpiryItem(k, expireAtMillis));
        }
    }

    // ---------------------------------------------------------------- purging

    /**
     * Active expiry: removes every key whose deadline has passed. Called periodically.
     *
     * @return how many keys were removed
     */
    public int purgeExpired() {
        int removed = 0;
        ExpiryItem item;
        while ((item = expiryQueue.poll()) != null) { // poll() returns null unless the head is due
            if (removeIfExpired(item.key)) {
                removed++;
            }
        }
        return removed;
    }

    // ---------------------------------------------------------------- internals

    /** Lazy expiry: returns the live entry (optionally counting an access), or null after removing an expired one. */
    private Entry live(ByteArrayKey key, boolean touch) {
        Entry entry = data.get(key);
        if (entry == null) {
            return null;
        }
        long now = clock.millis();
        if (entry.isExpiredAt(now)) {
            removeIfSame(key, entry);
            return null;
        }
        if (touch) {
            entry.touch(now, random);
        }
        return entry;
    }

    private boolean removeIfExpired(ByteArrayKey key) {
        Entry entry = data.get(key);
        return entry != null
                && entry.isExpiredAt(clock.millis())
                && removeIfSame(key, entry);
    }

    /** Replaces whatever is stored under the key, atomically, with the bookkeeping. @return the previous entry */
    private Entry put(ByteArrayKey key, Entry updated) {
        Entry[] previous = new Entry[1];
        data.compute(key, (ignored, old) -> {
            previous[0] = old;
            track(key, old, updated);
            return updated;
        });
        return previous[0];
    }

    /** Removes the key, atomically, with the bookkeeping. @return the removed entry, or null */
    private Entry remove(ByteArrayKey key) {
        Entry[] previous = new Entry[1];
        data.computeIfPresent(key, (ignored, old) -> {
            previous[0] = old;
            track(key, old, null);
            return null;
        });
        return previous[0];
    }

    /** Removes the key only if it still maps to exactly this entry (never destroys a newer value). */
    private boolean removeIfSame(ByteArrayKey key, Entry expected) {
        boolean[] removed = {false};
        data.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            removed[0] = true;
            track(key, current, null);
            return null;
        });
        return removed[0];
    }

    private static long sizeOf(ByteArrayKey key, Entry entry) {
        return MemoryModel.ENTRY_OVERHEAD + MemoryModel.bytes(key.bytes()) + entry.value.estimatedBytes();
    }

    /** For changes where neither value was modified in place: sizes are read from the entries themselves. */
    private void track(ByteArrayKey key, Entry old, Entry updated) {
        track(key, old, old == null ? 0 : sizeOf(key, old), updated, updated == null ? 0 : sizeOf(key, updated));
    }

    /**
     * The one place that keeps usedMemory and both key indexes consistent. {@code old} / {@code updated} are the
     * entries before/after (null = absent) and the sizes are passed in because access() changes a collection in place.
     */
    private void track(ByteArrayKey key, Entry old, long oldSize, Entry updated, long newSize) {
        usedMemory.addAndGet(newSize - oldSize);

        if (old == null && updated != null) {
            allKeys.add(key);
        } else if (old != null && updated == null) {
            allKeys.remove(key);
        }

        boolean wasVolatile = old != null && old.expireAtMillis != NO_EXPIRY;
        boolean isVolatile = updated != null && updated.expireAtMillis != NO_EXPIRY;
        if (!wasVolatile && isVolatile) {
            volatileKeys.add(key);
        } else if (wasVolatile && !isVolatile) {
            volatileKeys.remove(key);
        }
    }

    private static KeySample describe(ByteArrayKey key, Entry entry, long now) {
        return new KeySample(key.bytes(), entry.lastAccessMillis, entry.decayedFrequency(now), entry.expireAtMillis);
    }

    /** now + ttl, saturating at Long.MAX_VALUE instead of overflowing into the past. */
    private static long expireAtFor(long ttlMillis, long nowMillis) {
        return ttlMillis > Long.MAX_VALUE - nowMillis ? Long.MAX_VALUE : nowMillis + ttlMillis;
    }
}