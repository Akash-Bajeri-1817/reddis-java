package com.reddis.storage;

import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreMemoryTest {

    private MutableClock clock;
    private DataStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        store = new DataStore(clock, new Random(1));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void usedMemoryStartsAtZero() {
        assertEquals(0, store.usedMemory());
    }

    @Test
    void setAddsTheEntrySizeAndDeleteSubtractsIt() {
        store.set(b("k"), b("value"));
        assertTrue(store.usedMemory() > 0);
        assertEquals(store.memoryUsage(b("k")), store.usedMemory());
        store.delete(b("k"));
        assertEquals(0, store.usedMemory());
    }

    @Test
    void overwritingReplacesTheOldSize() {
        store.set(b("k"), b("0123456789"));
        long small = store.usedMemory();
        store.set(b("k"), b("x".repeat(1000)));
        assertTrue(store.usedMemory() > small);
        assertEquals(store.memoryUsage(b("k")), store.usedMemory());
        store.set(b("k"), b("0123456789"));
        assertEquals(small, store.usedMemory());
    }

    @Test
    void biggerValuesCostExactlyTheirExtraBytes() {
        store.set(b("a"), b("0123456789"));
        store.set(b("b"), b("x".repeat(1000)));
        long difference = store.memoryUsage(b("b")) - store.memoryUsage(b("a"));
        assertEquals(MemoryModel.bytes(b("x".repeat(1000))) - MemoryModel.bytes(b("0123456789")), difference);
    }

    @Test
    void collectionsTrackGrowthAndShrinkageAndVanishWhenEmpty() {
        for (int i = 0; i < 100; i++) {
            store.access(b("list"), ListValue.class, ListValue::new, list -> {
                list.pushRight(b("item"));
                return null;
            });
        }
        assertEquals(store.memoryUsage(b("list")), store.usedMemory());
        long full = store.usedMemory();

        for (int i = 0; i < 99; i++) {
            store.access(b("list"), ListValue.class, ListValue::new, ListValue::popLeft);
        }
        assertTrue(store.usedMemory() < full);
        store.access(b("list"), ListValue.class, ListValue::new, ListValue::popLeft); // the last one
        assertEquals(0, store.usedMemory());
        assertEquals(0, store.size());
    }

    @Test
    void expiredEntriesCountUntilTheyAreActuallyRemoved() {
        store.set(b("k"), b("v"), 100);
        long used = store.usedMemory();
        assertTrue(used > 0);
        clock.advance(100);
        assertEquals(used, store.usedMemory()); // logically expired, still stored
        store.purgeExpired();
        assertEquals(0, store.usedMemory());
    }

    @Test // NFR-8
    void theCounterAndBothIndexesStayConsistentUnderRandomOperations() {
        Random random = new Random(2024);
        for (int step = 1; step <= 3_000; step++) {
            byte[] key = b("k" + random.nextInt(40));
            try {
                switch (random.nextInt(14)) {
                    case 0 -> store.set(key, b("v".repeat(random.nextInt(50))));
                    case 1 -> store.set(key, b("x"), 1 + random.nextInt(200));
                    case 2 -> store.access(key, ListValue.class, ListValue::new, list -> {
                        list.pushRight(b("e" + random.nextInt(100)));
                        return null;
                    });
                    case 3 -> store.access(key, ListValue.class, ListValue::new, ListValue::popLeft);
                    case 4 -> store.access(key, HashValue.class, HashValue::new,
                            hash -> hash.put(b("f" + random.nextInt(5)), b("v".repeat(random.nextInt(20)))));
                    case 5 -> store.access(key, HashValue.class, HashValue::new,
                            hash -> hash.remove(b("f" + random.nextInt(5))));
                    case 6 -> store.access(key, SetValue.class, SetValue::new,
                            set -> set.add(b("m" + random.nextInt(8))));
                    case 7 -> store.access(key, SetValue.class, SetValue::new,
                            set -> set.remove(b("m" + random.nextInt(8))));
                    case 8 -> store.access(key, SortedSetValue.class, SortedSetValue::new,
                            zset -> zset.add(b("m" + random.nextInt(8)), random.nextInt(10)));
                    case 9 -> store.access(key, SortedSetValue.class, SortedSetValue::new,
                            zset -> zset.remove(b("m" + random.nextInt(8))));
                    case 10 -> store.delete(key);
                    case 11 -> store.expire(key, random.nextInt(300));
                    case 12 -> store.persist(key);
                    default -> {
                        clock.advance(random.nextInt(60));
                        store.purgeExpired();
                    }
                }
            } catch (WrongTypeException ignored) {
                // the random key held another type: expected, nothing changed
            }
            if (step % 100 == 0) {
                store.validate(); // indexes and the memory counter must agree with the map
            }
        }
        store.validate();
        assertEquals(store.recomputeUsedMemory(), store.usedMemory());
    }
}