package com.reddis.storage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A Redis set of unique members. Keeps a running estimate of its memory use.
 * NOT thread-safe: only use it inside DataStore.access().
 */
public final class SetValue implements RedisValue {

    private final Set<ByteArrayKey> members = new LinkedHashSet<>();
    private long bytes = MemoryModel.SET_BASE;

    /** @return true if the member was new */
    public boolean add(byte[] member) {
        boolean added = members.add(new ByteArrayKey(member));
        if (added) {
            bytes += MemoryModel.SET_ENTRY + MemoryModel.bytes(member);
        }
        return added;
    }

    /** @return true if the member existed */
    public boolean remove(byte[] member) {
        boolean removed = members.remove(new ByteArrayKey(member));
        if (removed) {
            bytes -= MemoryModel.SET_ENTRY + MemoryModel.bytes(member);
        }
        return removed;
    }

    public boolean contains(byte[] member) {
        return members.contains(new ByteArrayKey(member));
    }

    public int size() {
        return members.size();
    }

    /** All members, as a copy. */
    public List<byte[]> members() {
        List<byte[]> result = new ArrayList<>(members.size());
        for (ByteArrayKey member : members) {
            result.add(member.bytes());
        }
        return result;
    }

    @Override
    public String typeName() {
        return "set";
    }

    @Override
    public boolean isEmpty() {
        return members.isEmpty();
    }

    @Override
    public long estimatedBytes() {
        return bytes;
    }

    /** Recomputes the estimate from scratch (O(n)); the running total must always equal it. */
    long recountBytes() {
        long total = MemoryModel.SET_BASE;
        for (ByteArrayKey member : members) {
            total += MemoryModel.SET_ENTRY + MemoryModel.bytes(member.bytes());
        }
        return total;
    }
}