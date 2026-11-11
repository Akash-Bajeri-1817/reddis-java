package com.reddis.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The running size estimate of every collection must always equal a from-scratch recount (NFR-8). */
class ValueMemoryAccountingTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] text(Random random) {
        return b("x".repeat(random.nextInt(40)) + random.nextInt(1000));
    }

    @Test
    void listEstimateMatchesARecount() {
        Random random = new Random(1);
        ListValue list = new ListValue();
        for (int step = 1; step <= 3_000; step++) {
            switch (random.nextInt(4)) {
                case 0 -> list.pushLeft(text(random));
                case 1 -> list.pushRight(text(random));
                case 2 -> list.popLeft();
                default -> list.popRight();
            }
            if (step % 50 == 0) {
                assertEquals(list.recountBytes(), list.estimatedBytes(), "after step " + step);
            }
        }
    }

    @Test
    void hashEstimateMatchesARecountIncludingOverwrites() {
        Random random = new Random(2);
        HashValue hash = new HashValue();
        for (int step = 1; step <= 3_000; step++) {
            byte[] field = b("f" + random.nextInt(30));
            if (random.nextInt(3) == 0) {
                hash.remove(field);
            } else {
                hash.put(field, text(random)); // overwrites with values of different sizes
            }
            if (step % 50 == 0) {
                assertEquals(hash.recountBytes(), hash.estimatedBytes(), "after step " + step);
            }
        }
    }

    @Test
    void setEstimateMatchesARecount() {
        Random random = new Random(3);
        SetValue set = new SetValue();
        for (int step = 1; step <= 3_000; step++) {
            byte[] member = b("m" + random.nextInt(60));
            if (random.nextBoolean()) {
                set.add(member);
            } else {
                set.remove(member);
            }
            if (step % 50 == 0) {
                assertEquals(set.recountBytes(), set.estimatedBytes(), "after step " + step);
            }
        }
    }

    @Test
    void sortedSetEstimateMatchesARecountIncludingScoreUpdates() {
        Random random = new Random(4);
        SortedSetValue zset = new SortedSetValue();
        for (int step = 1; step <= 3_000; step++) {
            byte[] member = b("m" + random.nextInt(60));
            if (random.nextInt(3) == 0) {
                zset.remove(member);
            } else {
                zset.add(member, random.nextInt(20)); // frequent score updates of existing members
            }
            if (step % 50 == 0) {
                assertEquals(zset.recountBytes(), zset.estimatedBytes(), "after step " + step);
            }
        }
    }

    @Test
    void anEmptiedCollectionReturnsToItsBaseSize() {
        ListValue list = new ListValue();
        long base = list.estimatedBytes();
        list.pushRight(b("a"));
        list.pushLeft(b("bb"));
        list.popLeft();
        list.popRight();
        assertEquals(base, list.estimatedBytes());
    }
}