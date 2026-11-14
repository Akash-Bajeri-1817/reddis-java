package com.reddis.server;

import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.PersistenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A real master and a real replica (two full servers) replicating over real sockets. */
@Timeout(180)
class ReplicationIntegrationTest {

    @TempDir
    Path dir;

    private TestServer master;
    private TestServer replica;

    @BeforeEach
    void start() throws IOException {
        master = new TestServer(new PersistenceConfig(dir.resolve("master"), false, FsyncPolicy.EVERYSEC, 0));
        replica = new TestServer(new PersistenceConfig(dir.resolve("replica"), false, FsyncPolicy.EVERYSEC, 0));
    }

    @AfterEach
    void stop() {
        replica.close();
        master.close();
    }

    // ---------------------------------------------------------------- helpers

    private void attach(TestClient replicaClient) throws IOException {
        assertEquals("OK", replicaClient.send("REPLICAOF", "127.0.0.1", Integer.toString(master.port)));
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    /** Replication is asynchronous: poll until the condition holds (or fail after 10 seconds). */
    private static void await(String description, Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("timed out waiting for: " + description);
    }

    private static void awaitReply(TestClient client, String expected, String... command) throws Exception {
        await(String.join(" ", command) + " to return " + expected, () -> expected.equals(client.send(command)));
    }

    private static String field(String info, String name) {
        return Arrays.stream(info.split("\r\n"))
                .filter(line -> line.startsWith(name + ":"))
                .map(line -> line.substring(name.length() + 1))
                .findFirst().orElse(null);
    }

    private static void awaitLinkUp(TestClient replicaClient) throws Exception {
        await("the replica link to come up",
                () -> "up".equals(field(replicaClient.send("INFO", "replication"), "master_link_status")));
    }

    // ------------------------------------------------------------------ tests

    @Test // AC-1
    void aReplicaReceivesTheExistingDataThenLiveWrites() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            m.send("SET", "a", "1");
            m.send("RPUSH", "l", "x", "y");

            attach(r);
            awaitReply(r, "1", "GET", "a");                 // came with the snapshot
            assertEquals("[x,y]", r.send("LRANGE", "l", "0", "-1"));

            m.send("SET", "b", "2");                         // arrives through the live stream
            awaitReply(r, "2", "GET", "b");
        }
    }

    @Test // AC-3
    void aReplicaRejectsClientWritesButServesReads() throws Exception {
        try (TestClient r = new TestClient(replica.port)) {
            attach(r);
            assertEquals("-READONLY You can't write against a read only replica.", r.send("SET", "x", "1"));
            assertEquals("-READONLY You can't write against a read only replica.", r.send("DEL", "x"));
            assertEquals("(nil)", r.send("GET", "x"));
            assertEquals("PONG", r.send("PING"));
        }
    }

    @Test // AC-1, AC-2
    void everyDataTypeAndTimeoutIsReplicated() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            m.send("SET", "s", "v");
            m.send("RPUSH", "l", "a", "b");
            m.send("HSET", "h", "f", "val");
            m.send("SADD", "st", "member");
            m.send("ZADD", "z", "1.5", "x");
            m.send("SET", "t", "temp", "EX", "100");

            attach(r);
            awaitReply(r, "v", "GET", "s");

            assertEquals("[a,b]", r.send("LRANGE", "l", "0", "-1"));
            assertEquals("val", r.send("HGET", "h", "f"));
            assertEquals("1", r.send("SISMEMBER", "st", "member"));
            assertEquals("1.5", r.send("ZSCORE", "z", "x"));
            long ttl = Long.parseLong(r.send("TTL", "t"));
            assertTrue(ttl > 0 && ttl <= 100, "ttl: " + ttl); // an absolute deadline, not a fresh 100 seconds
        }
    }

    @Test // AC-1, AC-2
    void writeCommandsAreStreamedInOrder() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            attach(r);
            awaitLinkUp(r);

            m.send("MSET", "a", "1", "b", "2");
            m.send("DEL", "a");
            m.send("LPUSH", "l", "x");
            m.send("EXPIRE", "b", "1000");
            m.send("PERSIST", "b");
            m.send("ZADD", "z", "1", "m");
            m.send("SET", "done", "yes");

            awaitReply(r, "yes", "GET", "done"); // the stream is ordered: everything before it has been applied
            assertEquals("(nil)", r.send("GET", "a"));
            assertEquals("2", r.send("GET", "b"));
            assertEquals("-1", r.send("TTL", "b"));
            assertEquals("[x]", r.send("LRANGE", "l", "0", "-1"));
            assertEquals("1", r.send("ZSCORE", "z", "m"));
        }
    }

    @Test // AC-1
    void aTransactionIsReplicated() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            attach(r);
            awaitLinkUp(r);

            m.send("MULTI");
            m.send("SET", "t1", "1");
            m.send("RPUSH", "t2", "a");
            assertEquals("[OK,1]", m.send("EXEC"));

            awaitReply(r, "1", "GET", "t1");
            awaitReply(r, "[a]", "LRANGE", "t2", "0", "-1");
        }
    }

    @Test // AC-10
    void aFullResyncReplacesWhateverTheReplicaHeldBefore() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            assertEquals("OK", r.send("SET", "mine", "1")); // the replica is still a master here
            m.send("SET", "theirs", "2");

            attach(r);
            awaitReply(r, "2", "GET", "theirs");
            assertEquals("(nil)", r.send("GET", "mine"));
        }
    }

    @Test // AC-4
    void theReplicaReconnectsAndResumesWithAPartialResync() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            m.send("SET", "before", "1");
            attach(r);
            awaitReply(r, "1", "GET", "before");
            awaitLinkUp(r);

            replica.replication.dropConnection();   // the link breaks...
            m.send("SET", "during", "outage");      // ...and the master keeps writing

            awaitReply(r, "outage", "GET", "during");
            String info = m.send("INFO", "replication");
            assertEquals("1", field(info, "sync_full"));         // only the first sync copied the dataset
            assertEquals("1", field(info, "sync_partial_ok"));   // the reconnect only fetched what was missing
        }
    }

    @Test // AC-5
    void infoReportsRolesOffsetsAndAcknowledgements() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            attach(r);
            awaitLinkUp(r);
            m.send("SET", "k", "v");
            m.send("SET", "k2", "v2");

            await("the replica to report the master's offset", () -> {
                String masterInfo = m.send("INFO", "replication");
                String slave = field(masterInfo, "slave0");
                return "1".equals(field(masterInfo, "connected_slaves"))
                        && slave != null
                        && slave.startsWith("offset=" + field(masterInfo, "master_repl_offset") + ",");
            });

            String replicaInfo = r.send("INFO", "replication");
            assertEquals("slave", field(replicaInfo, "role"));
            assertEquals("127.0.0.1", field(replicaInfo, "master_host"));
            assertEquals(Integer.toString(master.port), field(replicaInfo, "master_port"));
            assertEquals(field(m.send("INFO", "replication"), "master_repl_offset"), field(replicaInfo, "slave_repl_offset"));
            assertTrue(r.send("ROLE").startsWith("[slave,127.0.0.1,"));
        }
    }

    @Test // AC-9
    void promotingAReplicaMakesItAWritableMaster() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            attach(r);
            awaitLinkUp(r);

            assertEquals("OK", r.send("REPLICAOF", "NO", "ONE"));
            assertEquals("OK", r.send("SET", "own", "1"));
            assertEquals("master", field(r.send("INFO", "replication"), "role"));

            m.send("SET", "later", "x");                     // the old master's writes no longer arrive
            Thread.sleep(300);
            assertEquals("(nil)", r.send("GET", "later"));
        }
    }

    @Test
    void aReplicaDoesNotServeReplicasOfItsOwn() throws Exception {
        try (TestClient r = new TestClient(replica.port)) {
            attach(r);
            assertTrue(r.send("PSYNC", "?", "-1").startsWith("-ERR PSYNC is not supported by a replica"));
        }
    }
}