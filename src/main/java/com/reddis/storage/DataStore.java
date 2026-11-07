package com.reddis.storage;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory key-value storage. Knows nothing about RESP or commands.
 *
 * Thread-safe: every method is atomic for the single key it touches, so many client
 * threads can use one DataStore at the same time. Operations on DIFFERENT keys are not
 * atomic as a group (e.g. DEL a b c); Module 6's single-threaded event loop restores that.
 *
 * Values are never null (ConcurrentHashMap forbids null values).
 */
public class DataStore {

    private final Map<ByteArrayKey, byte[]> data = new ConcurrentHashMap<>();

    /** Stores the value, replacing any existing one. */
    public void set(byte[] key, byte[] value) {
        data.put(new ByteArrayKey(key), Objects.requireNonNull(value, "value must not be null"));
    }

    /** @return the stored value, or null if the key does not exist */
    public byte[] get(byte[] key) {
        return data.get(new ByteArrayKey(key));
    }

    /** @return true if the key existed and was removed (atomic: only one caller can win) */
    public boolean delete(byte[] key) {
        return data.remove(new ByteArrayKey(key)) != null;
    }

    public boolean exists(byte[] key) {
        return data.containsKey(new ByteArrayKey(key));
    }

    public int size() {
        return data.size();
    }
}