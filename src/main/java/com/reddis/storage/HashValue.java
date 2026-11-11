package com.reddis.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Redis hash: field -> value. LinkedHashMap keeps insertion order, so output is deterministic.
 * Keeps a running estimate of its memory use. NOT thread-safe: only use it inside DataStore.access().
 */
public final class HashValue implements RedisValue {

    private final Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
    private long bytes = MemoryModel.HASH_BASE;

    /** @return true if the field is new, false if an existing field was overwritten */
    public boolean put(byte[] field, byte[] value) {
        byte[] old = fields.put(new ByteArrayKey(field), value);
        if (old == null) {
            bytes += MemoryModel.HASH_ENTRY + MemoryModel.bytes(field) + MemoryModel.bytes(value);
            return true;
        }
        bytes += MemoryModel.bytes(value) - MemoryModel.bytes(old);
        return false;
    }

    /** @return the value, or null if the field does not exist */
    public byte[] get(byte[] field) {
        return fields.get(new ByteArrayKey(field));
    }

    /** @return true if the field existed */
    public boolean remove(byte[] field) {
        byte[] old = fields.remove(new ByteArrayKey(field));
        if (old == null) {
            return false;
        }
        bytes -= MemoryModel.HASH_ENTRY + MemoryModel.bytes(field) + MemoryModel.bytes(old);
        return true;
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

    @Override
    public long estimatedBytes() {
        return bytes;
    }

    /** Recomputes the estimate from scratch (O(n)); the running total must always equal it. */
    long recountBytes() {
        long total = MemoryModel.HASH_BASE;
        for (Map.Entry<ByteArrayKey, byte[]> entry : fields.entrySet()) {
            total += MemoryModel.HASH_ENTRY + MemoryModel.bytes(entry.getKey().bytes()) + MemoryModel.bytes(entry.getValue());
        }
        return total;
    }
}