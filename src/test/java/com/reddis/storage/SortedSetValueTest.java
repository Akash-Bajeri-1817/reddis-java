package com.reddis.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortedSetValueTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> names(List<ScoredMember> items) {
        return items.stream().map(i -> new String(i.member(), StandardCharsets.UTF_8)).toList();
    }

    /** a=1, b=2, c=3 */
    private static SortedSetValue abc() {
        SortedSetValue zset = new SortedSetValue();
        zset.add(b("a"), 1);
        zset.add(b("b"), 2);
        zset.add(b("c"), 3);
        return zset;
    }

    @Test
    void addReturnsTrueOnlyForNewMembers() {
        SortedSetValue zset = new SortedSetValue();
        assertTrue(zset.add(b("a"), 1));
        assertFalse(zset.add(b("a"), 5));
        assertEquals(1, zset.size());
    }

    @Test
    void updatingAScoreMovesTheMember() {
        SortedSetValue zset = abc();
        zset.add(b("a"), 10);
        assertEquals(List.of("b", "c", "a"), names(zset.range(0, -1, false)));
        assertEquals(2, zset.rank(b("a")));
        assertEquals(10.0, zset.score(b("a")), 0.0);
        assertEquals(3, zset.size());
    }

    @Test
    void updatingToTheSameScoreChangesNothing() {
        SortedSetValue zset = abc();
        assertFalse(zset.add(b("b"), 2));
        assertEquals(List.of("a", "b", "c"), names(zset.range(0, -1, false)));
    }

    @Test
    void removeDeletesFromBothIndexes() {
        SortedSetValue zset = abc();
        assertTrue(zset.remove(b("b")));
        assertNull(zset.score(b("b")));
        assertEquals(-1, zset.rank(b("b")));
        assertFalse(zset.remove(b("b")));
        assertEquals(List.of("a", "c"), names(zset.range(0, -1, false)));
    }

    @Test
    void rankFollowsAscendingOrder() {
        SortedSetValue zset = abc();
        assertEquals(0, zset.rank(b("a")));
        assertEquals(2, zset.rank(b("c")));
        assertEquals(-1, zset.rank(b("missing")));
    }

    @Test
    void rangeSupportsNegativeIndices() {
        assertEquals(List.of("b", "c"), names(abc().range(-2, -1, false)));
        assertEquals(List.of("a", "b"), names(abc().range(0, -2, false)));
    }

    @Test
    void reverseRangeStartsFromTheHighestScore() {
        SortedSetValue zset = abc();
        assertEquals(List.of("c", "b"), names(zset.range(0, 1, true)));
        assertEquals(List.of("c", "b", "a"), names(zset.range(0, -1, true)));
        assertEquals(List.of("b"), names(zset.range(1, 1, true)));
        assertEquals(List.of("a"), names(zset.range(-1, -1, true)));
    }

    @Test
    void rangeClampsOutOfBoundsAndHandlesEmptySets() {
        assertEquals(List.of("a", "b", "c"), names(abc().range(-100, 100, false)));
        assertTrue(abc().range(5, 10, false).isEmpty());
        assertTrue(abc().range(5, 10, true).isEmpty());
        assertTrue(new SortedSetValue().range(0, -1, false).isEmpty());
        assertTrue(new SortedSetValue().range(0, -1, true).isEmpty());
    }

    @Test
    void negativeZeroIsTreatedAsZero() {
        SortedSetValue zset = new SortedSetValue();
        zset.add(b("a"), -0.0);
        zset.add(b("b"), 0.0);
        // equal scores, so the tie is broken by member bytes: a before b
        assertEquals(List.of("a", "b"), names(zset.range(0, -1, false)));
        assertFalse(zset.add(b("a"), 0.0)); // same score as before: no move
    }

    @Test
    void aSetWhoseMembersAreAllRemovedIsEmpty() {
        SortedSetValue zset = abc();
        zset.remove(b("a"));
        zset.remove(b("b"));
        assertFalse(zset.isEmpty());
        zset.remove(b("c"));
        assertTrue(zset.isEmpty());
    }
}