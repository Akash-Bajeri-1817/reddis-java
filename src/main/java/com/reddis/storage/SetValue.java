package com.reddis.storage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A Redis set of unique members. NOT thread-safe: only use it inside DataStore.access().
 */
public final class SetValue implements RedisValue {

    private final Set<ByteArrayKey> members = new LinkedHashSet<>();

    /** @return true if the member was new */
    public boolean add(byte[] member) {
        return members.add(new ByteArrayKey(member));
    }

    /** @return true if the member existed */
    public boolean remove(byte[] member) {
        return members.remove(new ByteArrayKey(member));
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
}