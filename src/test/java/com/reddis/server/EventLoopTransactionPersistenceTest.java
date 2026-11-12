package com.reddis.server;

import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.PersistenceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(60)
class EventLoopTransactionPersistenceTest {

    @TempDir
    Path dir;

    @Test // AC-10
    void theCommandsOfAnExecutedTransactionSurviveARestart() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, true, FsyncPolicy.ALWAYS, 0);

        try (TestServer first = new TestServer(config);
             TestClient client = new TestClient(first.port)) {
            assertEquals("OK", client.send("MULTI"));
            assertEquals("QUEUED", client.send("SET", "a", "1"));
            assertEquals("QUEUED", client.send("RPUSH", "l", "x", "y"));
            assertEquals("[OK,2]", client.send("EXEC"));
        }

        try (TestServer second = new TestServer(config);
             TestClient client = new TestClient(second.port)) {
            assertEquals("1", client.send("GET", "a"));
            assertEquals("[x,y]", client.send("LRANGE", "l", "0", "-1"));
        }
    }

    @Test // AC-10
    void aDiscardedTransactionLeavesNothingInTheLog() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, true, FsyncPolicy.ALWAYS, 0);

        try (TestServer first = new TestServer(config);
             TestClient client = new TestClient(first.port)) {
            assertEquals("OK", client.send("MULTI"));
            assertEquals("QUEUED", client.send("SET", "ghost", "1"));
            assertEquals("OK", client.send("DISCARD"));
            assertEquals("OK", client.send("SET", "real", "1"));
        }

        try (TestServer second = new TestServer(config);
             TestClient client = new TestClient(second.port)) {
            assertEquals("(nil)", client.send("GET", "ghost"));
            assertEquals("1", client.send("GET", "real"));
        }
    }
}