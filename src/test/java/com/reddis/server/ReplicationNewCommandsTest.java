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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(120)
class ReplicationNewCommandsTest {

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

    private static void awaitReply(TestClient client, String expected, String... command) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String last = null;
        while (System.nanoTime() < deadline) {
            last = client.send(command);
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(25);
        }
        fail("expected '" + expected + "' from " + String.join(" ", command) + " but the last reply was '" + last + "'");
    }

    @Test // AC-5
    void theNewWriteCommandsReachTheReplica() throws Exception {
        try (TestClient m = new TestClient(master.port); TestClient r = new TestClient(replica.port)) {
            assertEquals("OK", r.send("REPLICAOF", "127.0.0.1", Integer.toString(master.port)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!r.send("INFO", "replication").contains("master_link_status:up") && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            while (!r.send("INFO", "replication").contains("master_link_status:up") && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }

            m.send("INCR", "c");
            m.send("INCR", "c");
            m.send("INCR", "c");
            m.send("APPEND", "s", "ab");
            m.send("APPEND", "s", "cd");
            m.send("SET", "lock", "id", "NX");
            assertEquals("(nil)", m.send("SET", "lock", "other", "NX"));
            m.send("RENAME", "s", "t");
            m.send("SET", "gone", "1");
            m.send("FLUSHALL");
            m.send("SET", "after", "1");
            m.send("INCRBY", "c", "10");
            m.send("RENAME", "after", "moved");
            m.send("SET", "done", "yes");

            awaitReply(r, "yes", "GET", "done");                // the stream is ordered: everything before is applied
            assertEquals("10", r.send("GET", "c"));             // counted again from 0 after the flush
            assertEquals("1", r.send("GET", "moved"));
            assertEquals("(nil)", r.send("GET", "after"));
            assertEquals("(nil)", r.send("GET", "t"));          // flushed
            assertEquals("(nil)", r.send("GET", "lock"));
        }
    }
}