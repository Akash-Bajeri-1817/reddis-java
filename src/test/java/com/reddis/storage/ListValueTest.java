package com.reddis.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> strings(List<byte[]> items) {
        return items.stream().map(i -> new String(i, StandardCharsets.UTF_8)).toList();
    }

    /** a, b, c, d (left to right) */
    private static ListValue abcd() {
        ListValue list = new ListValue();
        for (String s : new String[]{"a", "b", "c", "d"}) {
            list.pushRight(b(s));
        }
        return list;
    }

    @Test
    void pushLeftAndRightBuildTheExpectedOrder() {
        ListValue list = new ListValue();
        list.pushRight(b("a"));
        list.pushRight(b("b"));
        list.pushLeft(b("z"));
        assertEquals(List.of("z", "a", "b"), strings(list.range(0, -1)));
    }

    @Test
    void popsTakeFromTheRequestedEnd() {
        ListValue list = abcd();
        assertArrayEquals(b("a"), list.popLeft());
        assertArrayEquals(b("d"), list.popRight());
        assertEquals(2, list.size());
    }

    @Test
    void popOnAnEmptyListReturnsNull() {
        ListValue list = new ListValue();
        assertNull(list.popLeft());
        assertNull(list.popRight());
    }

    @Test
    void rangeWithPositiveIndicesIsInclusiveOnBothEnds() {
        assertEquals(List.of("b", "c"), strings(abcd().range(1, 2)));
    }

    @Test
    void rangeWithNegativeIndicesCountsFromTheEnd() {
        assertEquals(List.of("c", "d"), strings(abcd().range(-2, -1)));
        assertEquals(List.of("a", "b", "c", "d"), strings(abcd().range(0, -1)));
    }

    @Test
    void rangeClampsOutOfBoundsIndices() {
        assertEquals(List.of("a", "b", "c", "d"), strings(abcd().range(-100, 100)));
        assertEquals(List.of("c", "d"), strings(abcd().range(2, 100)));
    }

    @Test
    void rangeIsEmptyWhenStartIsAfterStopOrBeyondTheEnd() {
        assertTrue(abcd().range(3, 1).isEmpty());
        assertTrue(abcd().range(10, 20).isEmpty());
        assertTrue(new ListValue().range(0, -1).isEmpty());
    }

    @Test
    void getSupportsNegativeIndicesAndReturnsNullOutOfRange() {
        ListValue list = abcd();
        assertArrayEquals(b("a"), list.get(0));
        assertArrayEquals(b("d"), list.get(-1));
        assertNull(list.get(4));
        assertNull(list.get(-5));
    }
}