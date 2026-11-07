package com.reddis.storage;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * The in-memory key-value storage with key expiry. Knows nothing about RESP or commands.
 *
 * Thread-safe: every method is atomic for the single key it touches.
 *
 * EXPIRY DESIGN
 *  - Each key maps to an immutable Entry(value, expireAtMillis). Changing a timeout replaces the
 *    whole Entry in one atomic map operation.
 *  - LAZY expiry: every access checks the deadline; an expired key behaves like a missing key and
 *    is removed on the spot.
 *  - ACTIVE expiry: every timed key also gets an ExpiryItem in a DelayQueue. purgeExpired() drains
 *    the items that are due and removes keys that really are expired. The queue is only a HINT:
 *    items can be stale (the key was overwritten, extended or persisted since), so each item is
 *    re-validated against the live entry before anything is deleted.
 */
public class DataStore {

    /** ttlMillis() result for a key that does not exist (or has expired). */
    public static final long TTL_MISSING = -2;
    /** ttlMillis() result for a key that exists but has no timeout. */
    public static final long TTL_NO_EXPIRY = -1;

    private static final long NO_EXPIRY = -1;

    /**
     * Immutable. Deliberately does NOT override equals(): the conditional remove(key, entry) relies on
     * identity comparison to delete exactly this entry and never a newer one.
     */
    private static final class Entry {
        final byte[] value;
        final long expireAtMillis; // absolute wall-clock time, or NO_EXPIRY

        Entry(byte[] value, long expireAtMillis) {
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

    // ---------------------------------------------------------------- writes

    /** Stores the value with no timeout, replacing any existing value AND any existing timeout. */
    public void set(byte[] key, byte[] value) {
        data.put(new ByteArrayKey(key), new Entry(Objects.requireNonNull(value, "value must not be null"), NO_EXPIRY));
    }

    /** Stores the value, expiring ttlMillis from now. Replaces any existing value and timeout. */
    public void set(byte[] key, byte[] value, long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        Objects.requireNonNull(value, "value must not be null");
        ByteArrayKey k = new ByteArrayKey(key);
        long expireAt = expireAtFor(ttlMillis);
        data.put(k, new Entry(value, expireAt));
        expiryQueue.add(new ExpiryItem(k, expireAt));
    }

    /**
     * Sets a timeout on an existing, live key. A zero or negative ttl deletes the key immediately.
     *
     * @return true if the key existed (and was updated or deleted), false if it was missing/expired
     */
    public boolean expire(byte[] key, long ttlMillis) {
        ByteArrayKey k = new ByteArrayKey(key);
        long now = clock.millis();
        long expireAt = expireAtFor(ttlMillis, now);
        boolean[] applied = {false};
        boolean[] scheduled = {false};

        // computeIfPresent runs atomically for this key: no other thread can change it meanwhile.
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

    // ----------------------------------------------------------------- reads

    /** @return the stored value, or null if the key does not exist or has expired */
    public byte[] get(byte[] key) {
        Entry entry = live(new ByteArrayKey(key));
        return entry == null ? null : entry.value;
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

    /** Number of stored entries, INCLUDING expired ones not yet purged. */
    public int size() {
        return data.size();
    }

    // ---------------------------------------------------------------- expiry

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
            data.remove(key, entry); // conditional: only if it is STILL this entry (see class notes)
            return null;
        }
        return entry;
    }

    private boolean removeIfExpired(ByteArrayKey key) {
        Entry entry = data.get(key);
        return entry != null
                && entry.isExpiredAt(clock.millis())
                && data.remove(key, entry); // false if someone replaced the entry in the meantime
    }

    private long expireAtFor(long ttlMillis) {
        return expireAtFor(ttlMillis, clock.millis());
    }

    /** now + ttl, saturating at Long.MAX_VALUE instead of overflowing into the past. */
    private static long expireAtFor(long ttlMillis, long nowMillis) {
        return ttlMillis > Long.MAX_VALUE - nowMillis ? Long.MAX_VALUE : nowMillis + ttlMillis;
    }
}