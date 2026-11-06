package com.reddis.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * The in-memory key-value storage. Knows nothing about RESP or commands.
 *
 * NOT THREAD-SAFE (fixed in Module 3). It is safe for now only because the server
 * handles one client at a time.
 */
public class DataStore {

    private final Map<ByteArrayKey, byte[]> data = new HashMap<>();

    /** Stores the value, replacing any existing one. */
    public void set(byte[] key, byte[] value) {
        data.put(new ByteArrayKey(key), value);
    }

    /** @return the stored value, or null if the key does not exist */
    public byte[] get(byte[] key) {
        return data.get(new ByteArrayKey(key));
    }

    /** @return true if the key existed and was removed */
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