package com.reddis.replication;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationBacklogTest {

    private static List<byte[]> record(String... parts) {
        return java.util.Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    @Test
    void entriesAreNumberedByTheirStartOffsets() {
        ReplicationBacklog backlog = new ReplicationBacklog(1_000, 100);
        backlog.add(record("SET", "a", "1"), 10);
        backlog.add(record("SET", "b", "2"), 20);

        assertEquals(100, backlog.startOffset());
        assertEquals(130, backlog.endOffset());
        assertEquals(2, backlog.entriesFrom(100).size());
        assertEquals(1, backlog.entriesFrom(110).size());
        assertEquals(110, backlog.entriesFrom(110).get(0).startOffset());
        assertTrue(backlog.entriesFrom(130).isEmpty()); // fully caught up
    }

    @Test
    void anOffsetInsideACommandCannotBeServed() {
        ReplicationBacklog backlog = new ReplicationBacklog(1_000, 100);
        backlog.add(record("SET", "a", "1"), 10);
        assertNull(backlog.entriesFrom(105));
    }

    @Test
    void offsetsOutsideTheBacklogCannotBeServed() {
        ReplicationBacklog backlog = new ReplicationBacklog(1_000, 100);
        backlog.add(record("SET", "a", "1"), 10);
        assertNull(backlog.entriesFrom(99));
        assertNull(backlog.entriesFrom(111));
    }

    @Test
    void theOldestEntriesAreDroppedWhenTheBacklogIsFull() {
        ReplicationBacklog backlog = new ReplicationBacklog(50, 100);
        backlog.add(record("SET", "a", "1"), 30);
        backlog.add(record("SET", "b", "2"), 30); // 60 bytes > 50: the first command is dropped

        assertEquals(130, backlog.startOffset());
        assertNull(backlog.entriesFrom(100));
        assertEquals(1, backlog.entriesFrom(130).size());
        assertEquals(30, backlog.bytes());
    }

    @Test
    void theNewestEntryIsKeptEvenIfItAloneExceedsTheLimit() {
        ReplicationBacklog backlog = new ReplicationBacklog(10, 0);
        backlog.add(record("SET", "big", "value"), 100);
        assertEquals(1, backlog.size());
        assertEquals(1, backlog.entriesFrom(0).size());
    }

    @Test
    void anEmptyBacklogOnlyServesItsOwnOffset() {
        ReplicationBacklog backlog = new ReplicationBacklog(1_000, 500);
        assertTrue(backlog.entriesFrom(500).isEmpty());
        assertNull(backlog.entriesFrom(499));
        assertNull(backlog.entriesFrom(501));
    }
}