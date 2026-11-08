package com.reddis.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Redis hash: field -> value. LinkedHashMap keeps insertion order, so output is deterministic.
 * NOT thread-safe: only use it inside DataStore.access().
 */
public final class HashValue implements RedisValue {

    private final Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();

    /** @return true if the field is new, false if an existing field was overwritten */
    public boolean put(byte[] field, byte[] value) {
        return fields.put(new ByteArrayKey(field), value) == null;
    }

    /** @return the value, or null if the field does not exist */
    public byte[] get(byte[] field) {
        return fields.get(new ByteArrayKey(field));
    }

    /** @return true if the field existed */
    public boolean remove(byte[] field) {
        return fields.remove(new ByteArrayKey(field)) != null;
    }

    public boolean contains(byte[] field) {
        return fields.containsKey(new ByteArrayKey(field));
    }

    public int size() {
        return fields.size();
    }

    /** field1, value1, field2, value2, ... (the RESP2 shape of HGETALL). Returns a copy. */
    public List<byte[]> flatten() {
        List<byte[]> result = new ArrayList<>(fields.size() * 2);
        for (Map.Entry<ByteArrayKey, byte[]> entry : fields.entrySet()) {
            result.add(entry.getKey().bytes());
            result.add(entry.getValue());
        }
        return result;
    }

    @Override
    public String typeName() {
        return "hash";
    }

    @Override
    public boolean isEmpty() {
        return fields.isEmpty();
    }
}