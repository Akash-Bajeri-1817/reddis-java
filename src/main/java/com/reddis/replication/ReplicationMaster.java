package com.reddis.replication;

import com.reddis.command.CommandArgs;
import com.reddis.persistence.SnapshotCodec;
import com.reddis.protocol.RespValue;
import com.reddis.session.ReplySink;
import com.reddis.session.Session;
import com.reddis.storage.DataStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The master side: the replication ID and offset, the backlog, the attached replicas, PSYNC and REPLCONF.
 * Runs on the event-loop thread only. Until the first replica attaches nothing is recorded (no backlog, offset 0).
 */
public final class ReplicationMaster {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the master knows about one attached replica. */
    private static final class ReplicaInfo {
        long ackOffset;
        long lastAckMillis;

        ReplicaInfo(long ackOffset) {
            this.ackOffset = ackOffset;
            this.lastAckMillis = System.currentTimeMillis();
        }
    }

    private final DataStore store;
    private final long backlogBytes;
    private final Map<Session, ReplicaInfo> replicas = new LinkedHashMap<>();

    private String replId = newReplId();
    private long offset;
    private ReplicationBacklog backlog; // null until the first replica attaches
    private long fullSyncs;
    private long partialSyncs;

    public ReplicationMaster(DataStore store, long backlogBytes) {
        this.store = store;
        this.backlogBytes = backlogBytes;
    }

    /** Starts a NEW replication history (a different ID) at the given offset: used when a replica is promoted. */
    public void reset(long startOffset) {
        replId = newReplId();
        offset = startOffset;
        backlog = null;
        replicas.clear();
    }

    /**
     * Records one command of the write stream and forwards it to every attached replica. Call it for each record
     * produced by the propagator, in order.
     */
    public void propagate(List<byte[]> record) {
        if (backlog == null) {
            return; // nobody ever attached: there is no stream to keep
        }
        long length = encodedLength(record);
        offset += length;
        backlog.add(record, length);
        if (replicas.isEmpty()) {
            return;
        }
        RespValue message = toArray(record);
        for (Session replica : new ArrayList<>(replicas.keySet())) { // a slow replica may be closed meanwhile
            replica.push(message);
        }
    }

    /** PSYNC replid offset: a full or a partial resynchronisation, then the connection becomes a replica. */
    public void psync(Session session, List<byte[]> args, ReplySink out) throws IOException {
        if (args.size() != 2) {
            out.send(CommandArgs.wrongArgs("psync"));
            return;
        }
        OptionalLong requestedOffset = CommandArgs.parseLong(args.get(1));
        if (requestedOffset.isEmpty()) {
            out.send(CommandArgs.NOT_AN_INTEGER);
            return;
        }
        String requestedId = new String(args.get(0), StandardCharsets.UTF_8);

        if (backlog == null) {
            backlog = new ReplicationBacklog(backlogBytes, offset); // the stream starts now
        }

        List<ReplicationBacklog.Entry> missed =
                requestedId.equals(replId) ? backlog.entriesFrom(requestedOffset.getAsLong()) : null;
        if (missed != null) {
            // PARTIAL: same history and the replica's position is still in the backlog.
            out.send(new RespValue.SimpleString("CONTINUE " + replId));
            for (ReplicationBacklog.Entry entry : missed) {
                out.send(toArray(entry.record()));
            }
            replicas.put(session, new ReplicaInfo(requestedOffset.getAsLong()));
            partialSyncs++;
            return;
        }

        // FULL: we are on the loop thread, so this snapshot is exactly the state at `offset`, and everything after it
        // will be pushed to this connection as it happens. No gap and no duplicate.
        byte[] snapshot = SnapshotCodec.serialize(store);
        out.send(new RespValue.SimpleString("FULLRESYNC " + replId + " " + offset));
        out.send(new RespValue.BulkString(snapshot));
        replicas.put(session, new ReplicaInfo(offset));
        fullSyncs++;
    }

    /** REPLCONF ACK offset records how far a replica has applied the stream. An ACK gets no reply. */
    public void replconf(Session session, List<byte[]> args, ReplySink out) throws IOException {
        if (args.size() == 2 && CommandArgs.keyword(args.get(0)).equals("ACK")) {
            ReplicaInfo info = replicas.get(session);
            OptionalLong ack = CommandArgs.parseLong(args.get(1));
            if (info != null && ack.isPresent()) {
                info.ackOffset = ack.getAsLong();
                info.lastAckMillis = System.currentTimeMillis();
            }
            return;
        }
        out.send(new RespValue.SimpleString("OK")); // other REPLCONF options are accepted and ignored
    }

    /** A connection closed: if it was a replica, forget it. */
    public void replicaGone(Session session) {
        replicas.remove(session);
    }

    // ------------------------------------------------------------------ queries

    public String replId() {
        return replId;
    }

    public long offset() {
        return offset;
    }

    public int replicaCount() {
        return replicas.size();
    }

    public long fullSyncs() {
        return fullSyncs;
    }

    public long partialSyncs() {
        return partialSyncs;
    }

    /** The master part of INFO replication. */
    String infoText() {
        long now = System.currentTimeMillis();
        StringBuilder text = new StringBuilder();
        text.append("role:master\r\n");
        text.append("connected_slaves:").append(replicas.size()).append("\r\n");
        int index = 0;
        for (ReplicaInfo info : replicas.values()) {
            text.append("slave").append(index++).append(":offset=").append(info.ackOffset)
                    .append(",lag=").append((now - info.lastAckMillis) / 1000).append("\r\n");
        }
        text.append("master_replid:").append(replId).append("\r\n");
        text.append("master_repl_offset:").append(offset).append("\r\n");
        text.append("repl_backlog_active:").append(backlog == null ? 0 : 1).append("\r\n");
        text.append("repl_backlog_size:").append(backlogBytes).append("\r\n");
        text.append("repl_backlog_histlen:").append(backlog == null ? 0 : backlog.bytes()).append("\r\n");
        text.append("sync_full:").append(fullSyncs).append("\r\n");
        text.append("sync_partial_ok:").append(partialSyncs).append("\r\n");
        return text.toString();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The size in bytes of the command encoded as a RESP array of bulk strings. Offsets are counted with this, and the
     * replica counts the bytes it actually reads: the two must agree exactly (a test checks it against the real encoder).
     */
    static long encodedLength(List<byte[]> record) {
        long length = 1 + Integer.toString(record.size()).length() + 2;                 // *N\r\n
        for (byte[] part : record) {
            length += 1 + Integer.toString(part.length).length() + 2 + part.length + 2; // $len\r\n bytes \r\n
        }
        return length;
    }

    private static RespValue toArray(List<byte[]> record) {
        List<RespValue> elements = new ArrayList<>(record.size());
        for (byte[] part : record) {
            elements.add(new RespValue.BulkString(part));
        }
        return new RespValue.RespArray(elements);
    }

    private static String newReplId() {
        byte[] random = new byte[20];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random); // 40 hex characters, like Redis
    }
}