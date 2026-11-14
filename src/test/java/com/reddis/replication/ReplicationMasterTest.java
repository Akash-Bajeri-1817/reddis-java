package com.reddis.replication;

import com.reddis.persistence.AofFormat;
import com.reddis.persistence.SnapshotCodec;
import com.reddis.protocol.RespValue;
import com.reddis.protocol.RespWriter;
import com.reddis.session.Session;
import com.reddis.storage.DataStore;
import com.reddis.support.RespText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The master side without any sockets: replicas are plain Sessions that record what is pushed to them. */
class ReplicationMasterTest {

    @TempDir
    Path dir;

    private DataStore store;
    private ReplicationMaster master;

    @BeforeEach
    void setUp() {
        store = new DataStore();
        master = new ReplicationMaster(store, 10_000);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<byte[]> record(String... parts) {
        return Arrays.stream(parts).map(ReplicationMasterTest::b).toList();
    }

    /** A simulated replica connection. */
    private final class Replica {
        final Session session;
        final List<RespValue> pushed = new ArrayList<>();

        Replica() {
            this.session = new Session(pushed::add);
        }

        List<RespValue> psync(String id, long offset) throws IOException {
            List<RespValue> replies = new ArrayList<>();
            master.psync(session, record(id, Long.toString(offset)), replies::add);
            return replies;
        }
    }

    private static List<String> rendered(List<RespValue> values) {
        return values.stream().map(RespText::render).toList();
    }

    @Test // the offsets only work if this matches the real encoding byte for byte
    void encodedLengthMatchesTheRealRespEncoding() throws IOException {
        List<List<byte[]>> records = List.of(
                record("PING"),
                record("SET", "k", "v"),
                record("SET", "key", ""),
                List.of(b("SET"), new byte[]{0, (byte) 255, '\r', '\n'}, new byte[1000]),
                record("MSET", "a", "1", "b", "2", "c", "3", "d", "4", "e", "5", "f", "6"));

        for (List<byte[]> record : records) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AofFormat.encode(new RespWriter(out), record);
            assertEquals(out.size(), ReplicationMaster.encodedLength(record));
        }
    }

    @Test
    void nothingIsRecordedBeforeTheFirstReplicaAttaches() {
        master.propagate(record("SET", "a", "1"));
        assertEquals(0, master.offset());
        assertTrue(master.infoText().contains("repl_backlog_active:0"));
    }

    @Test // AC-1
    void theFirstSyncIsAFullResyncWithASnapshotOfTheData() throws Exception {
        store.set(b("a"), b("1"));
        Replica replica = new Replica();

        List<RespValue> replies = replica.psync("?", -1);

        assertEquals(2, replies.size());
        assertEquals("FULLRESYNC " + master.replId() + " 0", RespText.render(replies.get(0)));
        byte[] snapshot = assertInstanceOf(RespValue.BulkString.class, replies.get(1)).data();

        Path file = dir.resolve("sync.rdb");
        Files.write(file, snapshot);
        DataStore copy = new DataStore();
        SnapshotCodec.load(file, copy);
        assertArrayEquals(b("1"), copy.get(b("a")));

        assertEquals(1, master.replicaCount());
        assertEquals(1, master.fullSyncs());
    }

    @Test // AC-1
    void streamedCommandsReachTheReplicaAndAdvanceTheOffset() throws Exception {
        Replica replica = new Replica();
        replica.psync("?", -1);

        List<byte[]> command = record("SET", "b", "2");
        master.propagate(command);

        assertEquals(List.of("[SET,b,2]"), rendered(replica.pushed));
        assertEquals(ReplicationMaster.encodedLength(command), master.offset());
    }

    @Test // AC-4
    void aReconnectingReplicaWithAKnownOffsetGetsAPartialResync() throws Exception {
        Replica first = new Replica();
        first.psync("?", -1);
        String id = master.replId();

        List<byte[]> r1 = record("SET", "a", "1");
        List<byte[]> r2 = record("SET", "b", "2");
        List<byte[]> r3 = record("SET", "c", "3");
        master.propagate(r1);
        master.propagate(r2);
        master.propagate(r3);
        master.replicaGone(first.session); // the connection dropped after the first command

        Replica second = new Replica();
        List<RespValue> replies = second.psync(id, ReplicationMaster.encodedLength(r1));

        assertEquals(List.of("CONTINUE " + id, "[SET,b,2]", "[SET,c,3]"), rendered(replies));
        assertEquals(1, master.partialSyncs());
        assertEquals(1, master.fullSyncs()); // only the very first sync was full
    }

    @Test // AC-4
    void aReplicaThatIsFullyCaughtUpJustGetsContinue() throws Exception {
        Replica first = new Replica();
        first.psync("?", -1);
        master.propagate(record("SET", "a", "1"));
        master.replicaGone(first.session);

        Replica second = new Replica();
        assertEquals(List.of("CONTINUE " + master.replId()), rendered(second.psync(master.replId(), master.offset())));
    }

    @Test // AC-4
    void anUnknownReplicationIdForcesAFullResync() throws Exception {
        Replica first = new Replica();
        first.psync("?", -1);

        Replica second = new Replica();
        List<RespValue> replies = second.psync("deadbeef", 0);
        assertTrue(RespText.render(replies.get(0)).startsWith("FULLRESYNC "));
        assertEquals(2, master.fullSyncs());
    }

    @Test // AC-4
    void anOffsetThatFellOutOfTheBacklogForcesAFullResync() throws Exception {
        master = new ReplicationMaster(store, 50); // a tiny backlog
        Replica first = new Replica();
        first.psync("?", -1);
        String id = master.replId();
        for (int i = 0; i < 5; i++) {
            master.propagate(record("SET", "key" + i, "value" + i)); // far more than 50 bytes
        }
        master.replicaGone(first.session);

        Replica second = new Replica();
        List<RespValue> replies = second.psync(id, 0); // offset 0 has long been dropped from the backlog
        assertTrue(RespText.render(replies.get(0)).startsWith("FULLRESYNC "));
        assertEquals(0, master.partialSyncs());
    }

    @Test // AC-5
    void ackOffsetsAreRecordedAndReported() throws Exception {
        Replica replica = new Replica();
        replica.psync("?", -1);

        List<RespValue> replies = new ArrayList<>();
        master.replconf(replica.session, record("ACK", "123"), replies::add);

        assertTrue(replies.isEmpty()); // an ACK gets no reply
        assertTrue(master.infoText().contains("slave0:offset=123,"), master.infoText());
    }

    @Test
    void aGoneReplicaStopsReceiving() throws Exception {
        Replica replica = new Replica();
        replica.psync("?", -1);
        master.replicaGone(replica.session);

        master.propagate(record("SET", "a", "1"));

        assertTrue(replica.pushed.isEmpty());
        assertEquals(0, master.replicaCount());
    }

    @Test // AC-9
    void resetStartsANewHistoryWithANewId() throws Exception {
        Replica replica = new Replica();
        replica.psync("?", -1);
        String oldId = master.replId();

        master.reset(500);

        assertNotEquals(oldId, master.replId());
        assertEquals(500, master.offset());
        assertEquals(0, master.replicaCount());
        assertTrue(master.infoText().contains("repl_backlog_active:0"));

        Replica other = new Replica();
        assertTrue(RespText.render(other.psync(oldId, 0).get(0)).startsWith("FULLRESYNC "));
    }

    @Test
    void psyncValidatesItsArguments() throws Exception {
        List<RespValue> replies = new ArrayList<>();
        Session session = new Session(message -> { });

        master.psync(session, record("only-one"), replies::add);
        master.psync(session, record("id", "abc"), replies::add);

        assertEquals(List.of("-ERR wrong number of arguments for 'psync' command",
                "-ERR value is not an integer or out of range"), rendered(replies));
    }
}