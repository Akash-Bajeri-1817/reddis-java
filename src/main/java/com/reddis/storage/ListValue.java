package com.reddis.storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A Redis list on top of ArrayDeque (circular array: O(1) at both ends).
 * NOT thread-safe: only use it inside DataStore.access().
 */
public final class ListValue implements RedisValue {

    private final ArrayDeque<byte[]> items = new ArrayDeque<>();

    public void pushLeft(byte[] value) {
        items.addFirst(value);
    }

    public void pushRight(byte[] value) {
        items.addLast(value);
    }

    /** @return the removed element, or null if the list is empty */
    public byte[] popLeft() {
        return items.pollFirst();
    }

    /** @return the removed element, or null if the list is empty */
    public byte[] popRight() {
        return items.pollLast();
    }

    public int size() {
        return items.size();
    }

    /** Element at index (negative counts from the end: -1 is the last). null if out of range. */
    public byte[] get(long index) {
        long size = items.size();
        long position = index < 0 ? size + index : index;
        if (position < 0 || position >= size) {
            return null;
        }
        int current = 0;
        for (byte[] item : items) {
            if (current++ == position) {
                return item;
            }
        }
        return null; // unreachable: position is within bounds
    }

    /**
     * Elements from start to stop, BOTH inclusive. Negative indices count from the end.
     * Out-of-range indices are clamped; an empty result is an empty list. Returns a copy.
     */
    public List<byte[]> range(long start, long stop) {
        long size = items.size();
        if (start < 0) {
            start = Math.max(0, size + start);
        }
        if (stop < 0) {
            stop = size + stop;
        }
        if (stop >= size) {
            stop = size - 1;
        }
        if (start > stop) {
            return List.of();
        }

        List<byte[]> result = new ArrayList<>((int) (stop - start + 1));
        int current = 0;
        for (byte[] item : items) {
            if (current > stop) {
                break;
            }
            if (current >= start) {
                result.add(item);
            }
            current++;
        }
        return result;
    }

    @Override
    public String typeName() {
        return "list";
    }

    @Override
    public boolean isEmpty() {
        return items.isEmpty();
    }
}