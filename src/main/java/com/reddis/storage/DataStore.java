package com.reddis.storage;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The in-memory storage: key -> typed value (string, list, hash, set, ...), with optional expiry.
 * Knows nothing about RESP or commands.
 *
 * THREAD SAFETY
 *  - Every method is atomic for the single key it touches.
 *  - Strings are immutable byte arrays. Collections are MUTABLE and not thread-safe themselves, so they
 *    are only ever touched inside access(), which runs under ConcurrentHashMap.compute(): exclusive per key.
 *
 * EXPIRY DESIGN (see ADR-9, ADR-10)
 *  - Each key maps to an immutable Entry(value, expireAtMillis). Lazy expiry on access plus an active
 *    purge driven by a DelayQueue of (key, due time) hints that are re-validated before deleting.
 */
public class DataStore {

    /** ttlMillis() result for a key that does not exist (or has expired). */
    public static final long TTL_MISSING = -2;
    /** ttlMillis() result for a key that exists but has no timeout. */
    public static final long TTL_NO_EXPIRY = -1;

    private static final long NO_EXPIRY = -1;

    /**
     * Immutable wrapper of (value, deadline). Deliberately does NOT override equals(): the conditional
     * remove(key, entry) relies on identity comparison. Note the VALUE inside may be a mutable collection;
     * the Entry only guarantees that value and deadline are swapped together atomically.
     */
    private static final class Entry {
        final RedisValue value;
        final long expireAtMillis; // absolute wall-clock time, or NO_EXPIRY

        Entry(RedisValue value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpiredAt(long nowMillis) {
            return expireAtMillis != NO_EXPIRY && nowMillis >= expireAtMillis;
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
    private final Clock clock;

    public DataStore() {
        this(Clock.systemUTC());
    }

    /** The clock is injected so tests can control time. */
    public DataStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    // --------------------------------------------------------------- strings

    /** Stores a string with no timeout, replacing any existing value of ANY type and any timeout. */
    public void set(byte[] key, byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        data.put(new ByteArrayKey(key), new Entry(new StringValue(value), NO_EXPIRY));
    }

    /** Stores a string, expiring ttlMillis from now. Replaces any existing value and timeout. */
    public void set(byte[] key, byte[] value, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        Objects.requireNonNull(value, "value must not be null");
        ByteArrayKey k = new ByteArrayKey(key);
        long expireAt = expireAtFor(ttlMillis);
        data.put(k, new Entry(new StringValue(value), expireAt));
        expiryQueue.add(new ExpiryItem(k, expireAt));
    }

    /**
     * @return the string value, or null if the key does not exist or has expired
     * @throws WrongTypeException if the key holds something other than a string
     */
    public byte[] get(byte[] key) {
        Entry entry = live(new ByteArrayKey(key));
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
     *    So write commands create keys and read commands on missing keys are harmless.
     *  - Key holds a different type: throws WrongTypeException and nothing changes.
     *  - After the action, a collection that became empty is removed (Redis never keeps empty collections).
     *  - An existing key keeps its timeout.
     *
     * The action runs while the key is locked: keep it short, never call this store from inside it, and
     * never let a reference to the collection escape (return copies or primitives).
     *
     * @param type    type token, because generics are erased at runtime (see Concepts)
     * @param factory creates an empty collection, e.g. ListValue::new
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

            result.value = action.apply(collection);

            if (collection.isEmpty()) {
                return null;                      // delete the key (or never create it)
            }
            return live ? existing : new Entry(collection, NO_EXPIRY);
        });

        return result.value;
    }

    /** @return "string", "list", "hash", "set", ... or "none" if the key does not exist */
    public String type(byte[] key) {
        Entry entry = live(new ByteArrayKey(key));
        return entry == null ? "none" : entry.value.typeName();
    }

    // ------------------------------------------------- expiry (any value type)

    /**
     * Sets a timeout on an existing, live key of any type. A zero or negative ttl deletes the key immediately.
     *
     * @return true if the key existed (and was updated or deleted), false if it was missing/expired
     */
    public boolean expire(byte[] key, long ttlMillis) {
        ByteArrayKey k = new ByteArrayKey(key);
        long now = clock.millis();
        long expireAt = expireAtFor(ttlMillis, now);
        boolean[] applied = {false};
        boolean[] scheduled = {false};

        data.computeIfPresent(k, (ignored, entry) -> {
            if (entry.isExpiredAt(now)) {
                return null;                          // already dead: clean it up, report "missing"
            }
            applied[0] = true;
            if (ttlMillis <= 0) {
                return null;                          // timeout in the past: delete now
            }
            scheduled[0] = true;
            return new Entry(entry.value, expireAt);  // new immutable entry, same value
        });

        if (scheduled[0]) {
            expiryQueue.add(new ExpiryItem(k, expireAt));
        }
        return applied[0];
    }

    /** Removes the timeout from a live key. @return true only if a timeout was actually removed */
    public boolean persist(byte[] key) {
        long now = clock.millis();
        boolean[] removed = {false};
        data.computeIfPresent(new ByteArrayKey(key), (ignored, entry) -> {
            if (entry.isExpiredAt(now)) {
                return null;
            }
            if (entry.expireAtMillis == NO_EXPIRY) {
                return entry;
            }
            removed[0] = true;
            return new Entry(entry.value, NO_EXPIRY);
        });
        return removed[0];
    }

    /** @return true if the key was live and has been removed (an expired key counts as already gone) */
    public boolean delete(byte[] key) {
        Entry old = data.remove(new ByteArrayKey(key));
        return old != null && !old.isExpiredAt(clock.millis());
    }

    public boolean exists(byte[] key) {
        return live(new ByteArrayKey(key)) != null;
    }

    /**
     * @return TTL_MISSING (-2) if there is no live key, TTL_NO_EXPIRY (-1) if it has no timeout,
     *         otherwise the milliseconds remaining (never negative)
     */
    public long ttlMillis(byte[] key) {
        Entry entry = live(new ByteArrayKey(key));
        if (entry == null) {
            return TTL_MISSING;
        }
        if (entry.expireAtMillis == NO_EXPIRY) {
            return TTL_NO_EXPIRY;
        }
        return Math.max(0, entry.expireAtMillis - clock.millis());
    }

    /** Number of stored keys, INCLUDING expired ones not yet purged. */
    public int size() {
        return data.size();
    }

    // ---------------------------------------------------------------- purging

    /**
     * Active expiry: removes every key whose deadline has passed. Called periodically by ExpiryPurger.
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

    /** Lazy expiry: returns the live entry, or null (removing the entry first if it has expired). */
    private Entry live(ByteArrayKey key) {
        Entry entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpiredAt(clock.millis())) {
            data.remove(key, entry); // conditional: only if it is STILL this entry
            return null;
        }
        return entry;
    }

    private boolean removeIfExpired(ByteArrayKey key) {
        Entry entry = data.get(key);
        return entry != null
                && entry.isExpiredAt(clock.millis())
                && data.remove(key, entry);
    }

    private long expireAtFor(long ttlMillis) {
        return expireAtFor(ttlMillis, clock.millis());
    }

    /** now + ttl, saturating at Long.MAX_VALUE instead of overflowing into the past. */
    private static long expireAtFor(long ttlMillis, long nowMillis) {
        return ttlMillis > Long.MAX_VALUE - nowMillis ? Long.MAX_VALUE : nowMillis + ttlMillis;
    }
}