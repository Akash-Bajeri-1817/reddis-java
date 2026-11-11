package com.reddis.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RandomKeySetTest {

    private static ByteArrayKey key(int i) {
        return new ByteArrayKey(("k" + i).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void addReportsWhetherTheKeyWasNew() {
        RandomKeySet set = new RandomKeySet();
        assertTrue(set.add(key(1)));
        assertFalse(set.add(key(1)));
        assertEquals(1, set.size());
    }

    @Test
    void removeReportsWhetherTheKeyWasPresent() {
        RandomKeySet set = new RandomKeySet();
        set.add(key(1));
        assertTrue(set.remove(key(1)));
        assertFalse(set.remove(key(1)));
        assertEquals(0, set.size());
    }

    @Test
    void removingAKeyFromTheMiddleKeepsEveryOtherKeyReachable() {
        RandomKeySet set = new RandomKeySet();
        Set<ByteArrayKey> expected = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            set.add(key(i));
            expected.add(key(i));
        }
        set.remove(key(3));
        expected.remove(key(3));

        assertEquals(expected, set.snapshot());
        assertEquals(expected, new HashSet<>(set.sample(100, new Random(1))));
        assertFalse(set.contains(key(3)));
        assertTrue(set.contains(key(9))); // the former last key was moved into the freed slot
    }

    @Test
    void sampleReturnsEverythingWhenThereAreFewerKeysThanRequested() {
        RandomKeySet set = new RandomKeySet();
        set.add(key(1));
        set.add(key(2));
        assertEquals(2, set.sample(5, new Random(1)).size());
        assertTrue(new RandomKeySet().sample(5, new Random(1)).isEmpty());
    }

    @Test
    void sampleReturnsDistinctMembers() {
        RandomKeySet set = new RandomKeySet();
        for (int i = 0; i < 100; i++) {
            set.add(key(i));
        }
        List<ByteArrayKey> sample = set.sample(10, new Random(7));
        assertEquals(10, sample.size());
        assertEquals(10, new HashSet<>(sample).size());
        assertTrue(set.snapshot().containsAll(sample));
    }

    @Test
    void repeatedSamplesEventuallyReachEveryKey() {
        RandomKeySet set = new RandomKeySet();
        for (int i = 0; i < 20; i++) {
            set.add(key(i));
        }
        Random random = new Random(3);
        Set<ByteArrayKey> seen = new HashSet<>();
        for (int round = 0; round < 500; round++) {
            seen.addAll(set.sample(3, random));
        }
        assertEquals(set.snapshot(), seen);
    }

    @Test
    void randomOperationsMatchAReferenceSet() {
        Random random = new Random(42);
        RandomKeySet set = new RandomKeySet();
        Set<ByteArrayKey> reference = new HashSet<>();

        for (int step = 1; step <= 5_000; step++) {
            ByteArrayKey k = key(random.nextInt(200));
            if (random.nextBoolean()) {
                assertEquals(reference.add(k), set.add(k));
            } else {
                assertEquals(reference.remove(k), set.remove(k));
            }
            if (step % 500 == 0) {
                assertEquals(reference.size(), set.size());
                assertEquals(reference, set.snapshot());
                assertTrue(reference.containsAll(set.sample(5, random)));
            }
        }
    }
}