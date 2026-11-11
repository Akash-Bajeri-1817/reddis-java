package com.reddis.storage;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Redis sorted set: unique members, each with a score, ordered by (score, member bytes).
 * Combines a hash map (member -> score, O(1) lookups) with a skip list (ordering and rank, O(log n)),
 * exactly like Redis. Both are always updated together. Keeps a running estimate of its memory use.
 * NOT thread-safe: only use it inside DataStore.access().
 */
public final class SortedSetValue implements RedisValue {

    private final Map<ByteArrayKey, Double> scores = new HashMap<>();
    private final SkipList ordered = new SkipList();
    private long bytes = MemoryModel.ZSET_BASE;

    /**
     * Adds the member, or updates its score if it already exists.
     *
     * @return true if the member is new, false if it already existed (its score is still updated)
     */
    public boolean add(byte[] member, double score) {
        if (score == 0.0) {
            score = 0.0; // turns -0.0 into 0.0: they are equal for Redis, but Double.compare would order them
        }
        ByteArrayKey key = new ByteArrayKey(member);
        Double old = scores.get(key);
        if (old == null) {
            ordered.insert(score, member);
            scores.put(key, score);
            bytes += MemoryModel.ZSET_ENTRY + MemoryModel.bytes(member);
            return true;
        }
        if (old != score) {
            ordered.delete(old, member); // the position depends on the score: remove and re-insert
            ordered.insert(score, member);
            scores.put(key, score);
        }
        return false;
    }

    /** @return true if the member existed and was removed */
    public boolean remove(byte[] member) {
        Double old = scores.remove(new ByteArrayKey(member));
        if (old == null) {
            return false;
        }
        ordered.delete(old, member);
        bytes -= MemoryModel.ZSET_ENTRY + MemoryModel.bytes(member);
        return true;
    }

    /** @return the member's score, or null if it is not in the set */
    public Double score(byte[] member) {
        return scores.get(new ByteArrayKey(member));
    }

    /** @return the 0-based rank in ascending order, or -1 if the member is not in the set */
    public int rank(byte[] member) {
        Double score = scores.get(new ByteArrayKey(member));
        return score == null ? -1 : ordered.rank(score, member);
    }

    public int size() {
        return scores.size();
    }

    /**
     * Members by position, BOTH ends inclusive, with the same index rules as LRANGE (negative indices count
     * from the end, out-of-range values are clamped). With reverse=true, position 0 is the HIGHEST score.
     * Returns a new list.
     */
    public List<ScoredMember> range(long start, long stop, boolean reverse) {
        long size = ordered.size();
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

        if (!reverse) {
            return ordered.range((int) start, (int) stop);
        }
        // Position p counted from the top is ascending position (size - 1 - p).
        List<ScoredMember> ascending = ordered.range((int) (size - 1 - stop), (int) (size - 1 - start));
        Collections.reverse(ascending);
        return ascending;
    }

    @Override
    public String typeName() {
        return "zset";
    }

    @Override
    public boolean isEmpty() {
        return scores.isEmpty();
    }

    @Override
    public long estimatedBytes() {
        return bytes;
    }

    /** Recomputes the estimate from scratch (O(n)); the running total must always equal it. */
    long recountBytes() {
        long total = MemoryModel.ZSET_BASE;
        for (ByteArrayKey member : scores.keySet()) {
            total += MemoryModel.ZSET_ENTRY + MemoryModel.bytes(member.bytes());
        }
        return total;
    }
}