package com.reddis.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class SkipListTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> names(List<ScoredMember> items) {
        return items.stream().map(i -> new String(i.member(), StandardCharsets.UTF_8)).toList();
    }

    private static SkipList newList() {
        return new SkipList(new Random(1)); // seeded: reproducible shapes
    }

    @Test
    void anEmptyListBehaves() {
        SkipList list = newList();
        assertEquals(0, list.size());
        assertTrue(list.range(0, 0).isEmpty());
        assertEquals(-1, list.rank(1, b("a")));
        assertFalse(list.delete(1, b("a")));
        list.validate();
    }

    @Test
    void insertKeepsAscendingScoreOrder() {
        SkipList list = newList();
        list.insert(3, b("c"));
        list.insert(1, b("a"));
        list.insert(2, b("b"));
        assertEquals(List.of("a", "b", "c"), names(list.range(0, 2)));
        list.validate();
    }

    @Test
    void equalScoresAreOrderedByMemberBytes() {
        SkipList list = newList();
        list.insert(1, b("b"));
        list.insert(1, b("a"));
        list.insert(1, b("c"));
        assertEquals(List.of("a", "b", "c"), names(list.range(0, 2)));
    }

    @Test
    void memberBytesAreComparedAsUnsigned() {
        SkipList list = newList();
        list.insert(1, new byte[]{(byte) 0x80}); // 128 unsigned, but -128 as a signed Java byte
        list.insert(1, new byte[]{0x7f});
        List<ScoredMember> items = list.range(0, 1);
        assertEquals(0x7f, items.get(0).member()[0]);
        assertEquals((byte) 0x80, items.get(1).member()[0]);
    }

    @Test
    void rankIsTheZeroBasedPosition() {
        SkipList list = newList();
        list.insert(30, b("c"));
        list.insert(10, b("a"));
        list.insert(50, b("e"));
        list.insert(20, b("b"));
        list.insert(40, b("d"));
        assertEquals(0, list.rank(10, b("a")));
        assertEquals(2, list.rank(30, b("c")));
        assertEquals(4, list.rank(50, b("e")));
    }

    @Test
    void rankOfAnAbsentElementIsMinusOne() {
        SkipList list = newList();
        list.insert(10, b("a"));
        assertEquals(-1, list.rank(10, b("zzz"))); // same score, different member
        assertEquals(-1, list.rank(11, b("a")));   // same member, different score
    }

    @Test
    void deleteRemovesTheElementAndKeepsTheStructureValid() {
        SkipList list = newList();
        for (int i = 0; i < 10; i++) {
            list.insert(i, b("m" + i));
        }
        assertTrue(list.delete(3, b("m3")));
        assertTrue(list.delete(0, b("m0")));
        assertTrue(list.delete(9, b("m9")));
        list.validate();
        assertEquals(7, list.size());
        assertEquals(List.of("m1", "m2", "m4", "m5", "m6", "m7", "m8"), names(list.range(0, 6)));
        assertEquals(2, list.rank(4, b("m4")));
    }

    @Test
    void deleteOfAnAbsentElementReturnsFalse() {
        SkipList list = newList();
        list.insert(1, b("a"));
        assertFalse(list.delete(2, b("a")));
        assertFalse(list.delete(1, b("b")));
        assertEquals(1, list.size());
    }

    @Test
    void rangeReturnsTheRequestedSlice() {
        SkipList list = newList();
        for (int i = 0; i < 6; i++) {
            list.insert(i, b("m" + i));
        }
        assertEquals(List.of("m1", "m2", "m3"), names(list.range(1, 3)));
        assertEquals(List.of("m4", "m5"), names(list.range(4, 99))); // an end past the list is clamped
    }

    @Test
    void invalidRangeBoundsGiveAnEmptyResult() {
        SkipList list = newList();
        list.insert(1, b("a"));
        list.insert(2, b("b"));
        assertTrue(list.range(-1, 1).isEmpty());
        assertTrue(list.range(1, 0).isEmpty());
        assertTrue(list.range(5, 9).isEmpty());
    }

    @Test
    void infinitiesSortAtTheEnds() {
        SkipList list = newList();
        list.insert(Double.POSITIVE_INFINITY, b("top"));
        list.insert(0, b("zero"));
        list.insert(Double.NEGATIVE_INFINITY, b("bottom"));
        assertEquals(List.of("bottom", "zero", "top"), names(list.range(0, 2)));
    }

    /** A reference element for the model test. */
    private record Item(double score, byte[] member) { }

    @Test // AC-7: model-based randomized test
    @Timeout(60)
    void randomOperationsMatchAReferenceModel() {
        Random random = new Random(12345);
        SkipList list = new SkipList(new Random(99));
        Comparator<Item> order = Comparator.comparingDouble(Item::score)
                .thenComparing(Item::member, Arrays::compareUnsigned);
        TreeSet<Item> model = new TreeSet<>(order);

        for (int step = 1; step <= 20_000; step++) {
            Item item = new Item(random.nextInt(50), b("m" + random.nextInt(300)));
            if (random.nextInt(100) < 55) {
                if (model.add(item)) {
                    list.insert(item.score(), item.member());
                }
            } else {
                boolean removed = model.remove(item);
                assertEquals(removed, list.delete(item.score(), item.member()));
            }
            if (step % 1_000 == 0) {
                list.validate();
                assertEquals(model.size(), list.size());
            }
        }
        list.validate();

        List<Item> expected = new ArrayList<>(model);
        List<ScoredMember> actual = list.range(0, list.size() - 1);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).score(), actual.get(i).score());
            assertArrayEquals(expected.get(i).member(), actual.get(i).member());
            assertEquals(i, list.rank(expected.get(i).score(), expected.get(i).member()));
        }

        // random sub-ranges (these exercise the span-based jump to a position)
        for (int i = 0; i < 200 && !expected.isEmpty(); i++) {
            int first = random.nextInt(expected.size());
            int last = first + random.nextInt(expected.size() - first);
            List<ScoredMember> slice = list.range(first, last);
            assertEquals(last - first + 1, slice.size());
            assertArrayEquals(expected.get(first).member(), slice.get(0).member());
            assertArrayEquals(expected.get(last).member(), slice.get(slice.size() - 1).member());
        }
    }

    @Test
    @Timeout(60)
    void manySequentialInsertsKeepTheInvariants() {
        SkipList list = new SkipList(new Random(7));
        for (int i = 0; i < 50_000; i++) {
            list.insert(i, b("m" + i));
        }
        list.validate();
        assertEquals(50_000, list.size());
        assertEquals(31_337, list.rank(31_337, b("m31337")));
    }
}