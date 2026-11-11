package com.reddis.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A set with O(1) add, remove AND "give me a random member": an ArrayList of keys plus a map from key to its
 * position. Removal swaps the key with the last list element, so nothing ever shifts.
 */
final class RandomKeySet {

    private final List<ByteArrayKey> keys = new ArrayList<>();
    private final Map<ByteArrayKey, Integer> positions = new HashMap<>();

    /** @return true if the key was not already present */
    synchronized boolean add(ByteArrayKey key) {
        if (positions.containsKey(key)) {
            return false;
        }
        positions.put(key, keys.size());
        keys.add(key);
        return true;
    }

    /** @return true if the key was present */
    synchronized boolean remove(ByteArrayKey key) {
        Integer position = positions.remove(key);
        if (position == null) {
            return false;
        }
        int last = keys.size() - 1;
        ByteArrayKey moved = keys.remove(last); // O(1): removes the final slot
        if (position != last) {
            keys.set(position, moved);          // the former last key takes over the freed slot
            positions.put(moved, position);
        }
        return true;
    }

    synchronized boolean contains(ByteArrayKey key) {
        return positions.containsKey(key);
    }

    synchronized int size() {
        return keys.size();
    }

    /** Up to {@code count} DISTINCT random members (all of them if there are no more than {@code count}). */
    synchronized List<ByteArrayKey> sample(int count, Random random) {
        int size = keys.size();
        if (size <= count) {
            return new ArrayList<>(keys);
        }
        Set<Integer> chosen = new HashSet<>();
        List<ByteArrayKey> result = new ArrayList<>(count);
        while (result.size() < count) {
            int index = random.nextInt(size);
            if (chosen.add(index)) {
                result.add(keys.get(index));
            }
        }
        return result;
    }

    /** A copy of the current members (for validation and tests). */
    synchronized Set<ByteArrayKey> snapshot() {
        return new HashSet<>(keys);
    }
}