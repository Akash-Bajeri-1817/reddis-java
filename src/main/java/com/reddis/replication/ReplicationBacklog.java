package com.reddis.replication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The most recent part of the write stream, kept so a replica that lost its connection can catch up without a full
 * copy. Entries are whole commands with their byte offsets; the oldest are dropped when the total exceeds the limit.
 */
final class ReplicationBacklog {

    /** One command of the stream: where it starts, the command itself and its encoded size in bytes. */
    record Entry(long startOffset, List<byte[]> record, long length) {
        long endOffset() {
            return startOffset + length;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final long maxBytes;
    private long bytes;
    private long endOffset;

    /** @param startOffset the stream offset at which the first added command will begin */
    ReplicationBacklog(long maxBytes, long startOffset) {
        this.maxBytes = maxBytes;
        this.endOffset = startOffset;
    }

    void add(List<byte[]> record, long length) {
        entries.addLast(new Entry(endOffset, record, length));
        endOffset += length;
        bytes += length;
        while (bytes > maxBytes && entries.size() > 1) { // always keep the newest command, however big
            bytes -= entries.removeFirst().length();
        }
    }

    /** Offset of the oldest byte still available. */
    long startOffset() {
        Entry first = entries.peekFirst();
        return first == null ? endOffset : first.startOffset();
    }

    /** Offset just after the newest byte: the master's current offset. */
    long endOffset() {
        return endOffset;
    }

    long bytes() {
        return bytes;
    }

    int size() {
        return entries.size();
    }

    /**
     * The commands a replica that has applied everything before {@code offset} is still missing, in order.
     *
     * @return an empty list if it is fully caught up, or null if it cannot be served (the offset is outside the
     *         backlog, or does not fall on a command boundary): the caller must do a full resync
     */
    List<Entry> entriesFrom(long offset) {
        if (offset == endOffset) {
            return List.of();
        }
        if (offset < startOffset() || offset > endOffset) {
            return null;
        }
        List<Entry> result = new ArrayList<>();
        boolean found = false;
        for (Entry entry : entries) {
            if (!found && entry.startOffset() == offset) {
                found = true;
            }
            if (found) {
                result.add(entry);
            }
        }
        return found ? result : null;
    }
}