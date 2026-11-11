package com.reddis.demo;

import com.reddis.eviction.EvictionPolicy;
import com.reddis.eviction.MemoryConfig;
import com.reddis.eviction.MemoryGuard;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * NOT a test. Run main() to compare eviction policies on a skewed ("Zipf") workload: a few keys are requested very
 * often and most rarely, like real cache traffic. Cache-aside pattern: on a miss the key is inserted.
 * Prints the hit rate of each policy for a cache that holds 10% of the keys.
 */
public class EvictionSimulation {

    private static final int KEYS = 10_000;
    private static final int CAPACITY = 1_000;
    private static final int OPERATIONS = 300_000;
    private static final byte[] VALUE = new byte[100];

    public static void main(String[] args) {
        int[] workload = zipfWorkload(OPERATIONS, KEYS, 1.0, new Random(2024));

        System.out.printf("%d keys, cache holds %d (%.0f%%), %d requests%n%n",
                KEYS, CAPACITY, 100.0 * CAPACITY / KEYS, OPERATIONS);
        System.out.printf("%-34s %s%n", "policy", "hit rate");
        System.out.printf("%-34s %.4f%n", "exact LRU (LinkedHashMap)", exactLru(workload));
        System.out.printf("%-34s %.4f%n", "allkeys-lru, 1 sample (= random)", reddis(EvictionPolicy.ALLKEYS_LRU, 1, workload));
        System.out.printf("%-34s %.4f%n", "allkeys-lru, 5 samples", reddis(EvictionPolicy.ALLKEYS_LRU, 5, workload));
        System.out.printf("%-34s %.4f%n", "allkeys-lru, 10 samples", reddis(EvictionPolicy.ALLKEYS_LRU, 10, workload));
        System.out.printf("%-34s %.4f%n", "allkeys-lfu, 5 samples", reddis(EvictionPolicy.ALLKEYS_LFU, 5, workload));
        System.out.printf("%-34s %.4f%n", "allkeys-lfu, 10 samples", reddis(EvictionPolicy.ALLKEYS_LFU, 10, workload));
        System.out.printf("%-34s %.4f%n", "allkeys-random", reddis(EvictionPolicy.ALLKEYS_RANDOM, 5, workload));
    }

    /** Request i is key number id(i); key popularity follows 1 / rank^s. */
    static int[] zipfWorkload(int operations, int keys, double s, Random random) {
        double[] cumulative = new double[keys];
        double total = 0;
        for (int i = 0; i < keys; i++) {
            total += 1.0 / Math.pow(i + 1, s);
            cumulative[i] = total;
        }
        int[] workload = new int[operations];
        for (int i = 0; i < operations; i++) {
            int position = Arrays.binarySearch(cumulative, random.nextDouble() * total);
            workload[i] = position >= 0 ? position : -position - 1;
        }
        return workload;
    }

    /** The reference: an exact LRU cache is three lines of Java (access-order LinkedHashMap). */
    static double exactLru(int[] workload) {
        Map<Integer, Boolean> cache = new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                return size() > CAPACITY;
            }
        };
        long hits = 0;
        for (int id : workload) {
            if (cache.get(id) != null) {
                hits++;
            } else {
                cache.put(id, Boolean.TRUE);
            }
        }
        return (double) hits / workload.length;
    }

    /** The real thing: DataStore + MemoryGuard with the given policy and sample count. */
    static double reddis(EvictionPolicy policy, int samples, int[] workload) {
        MutableClock clock = new MutableClock(1_000_000L);
        Random random = new Random(7);
        DataStore store = new DataStore(clock, random);

        store.set(key(0), VALUE);
        long entrySize = store.usedMemory(); // every key has the same length, so every entry has the same size
        store.delete(key(0));

        MemoryGuard guard = new MemoryGuard(store,
                new MemoryConfig(entrySize * CAPACITY - 1, policy, samples), clock, random, evicted -> { });

        long hits = 0;
        for (int id : workload) {
            clock.advance(1); // one millisecond per request, so recency differs between keys
            byte[] key = key(id);
            if (store.get(key) != null) {
                hits++;
            } else {
                guard.beforeExecute("SET"); // make room, exactly like a real SET would
                store.set(key, VALUE);
            }
        }
        return (double) hits / workload.length;
    }

    private static byte[] key(int id) {
        return String.format("key:%06d", id).getBytes(StandardCharsets.UTF_8);
    }
}