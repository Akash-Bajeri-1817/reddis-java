package com.reddis.server;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.PersistenceCommands;
import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.Persistence;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** End to end: real sockets, real files, a real "restart" (a brand-new server and store on the same directory). */
@Timeout(60)
class EventLoopPersistenceTest {

    @TempDir
    Path dir;

    /** A complete server wired like Main does it. */
    private static final class RunningServer implements AutoCloseable {
        final Persistence persistence;
        final EventLoopServer server;
        final int port;

        RunningServer(PersistenceConfig config) throws IOException {
            DataStore store = new DataStore();
            CommandRouter router = CommandRegistry.standard(store);
            persistence = new Persistence(config, store, router);
            persistence.load();
            PersistenceCommands.register(router, persistence);

            server = new EventLoopServer(0, router, () -> {
                store.purgeExpired();
                persistence.cron();
            }, 20, persistence);
            server.start();
            port = server.getPort();
        }

        @Override
        public void close() {
            server.stop();
            persistence.close();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for a condition");
            }
            Thread.sleep(20);
        }
    }

    @Test // AC-11
    void aWriteAcknowledgedOverTheNetworkSurvivesARestart() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, true, FsyncPolicy.ALWAYS, 0);

        try (RunningServer first = new RunningServer(config);
             TestClient client = new TestClient(first.port)) {
            assertEquals("OK", client.send("SET", "name", "arun"));
            assertEquals("2", client.send("RPUSH", "tasks", "write", "test"));
            assertEquals("OK", client.send("SET", "session", "abc", "EX", "3600"));
        }

        try (RunningServer second = new RunningServer(config);
             TestClient client = new TestClient(second.port)) {
            assertEquals("arun", client.send("GET", "name"));
            assertEquals("[write,test]", client.send("LRANGE", "tasks", "0", "-1"));
            long ttl = Long.parseLong(client.send("TTL", "session"));
            assertTrue(ttl > 3_500 && ttl <= 3_600, "ttl was " + ttl);
            assertEquals("3", client.send("DBSIZE"));
        }
    }

    @Test
    void saveOverTheNetworkWritesASnapshotThatSurvivesARestart() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0);

        try (RunningServer first = new RunningServer(config);
             TestClient client = new TestClient(first.port)) {
            assertEquals("OK", client.send("SET", "k", "v"));
            assertEquals("OK", client.send("SAVE"));
        }
        try (RunningServer second = new RunningServer(config);
             TestClient client = new TestClient(second.port)) {
            assertEquals("v", client.send("GET", "k"));
        }
    }

    @Test // AC-10
    void bgsaveReturnsImmediatelyAndTheSnapshotAppearsLater() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0);

        try (RunningServer first = new RunningServer(config);
             TestClient client = new TestClient(first.port)) {
            assertEquals("OK", client.send("SET", "k", "v"));
            assertEquals("Background saving started", client.send("BGSAVE"));
            waitUntil(() -> !first.persistence.isBackgroundSaveRunning() && Files.exists(config.rdbPath()));
            assertTrue(Long.parseLong(client.send("LASTSAVE")) > 0);
        }
        try (RunningServer second = new RunningServer(config);
             TestClient client = new TestClient(second.port)) {
            assertEquals("v", client.send("GET", "k"));
        }
    }
}