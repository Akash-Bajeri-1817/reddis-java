package com.reddis.server;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.PersistenceCommands;
import com.reddis.eviction.MemoryCommands;
import com.reddis.eviction.MemoryConfig;
import com.reddis.eviction.MemoryGuard;
import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.Persistence;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** End to end: real sockets, a real memory guard wired like Main does it, and a real restart. */
@Timeout(60)
class EventLoopMemoryTest {

    @TempDir
    Path dir;

    private static final class RunningServer implements AutoCloseable {
        final EventLoopServer server;
        final Persistence persistence;
        final int port;

        RunningServer(PersistenceConfig config) throws IOException {
            DataStore store = new DataStore();
            CommandRouter router = CommandRegistry.standard(store);
            persistence = new Persistence(config, store, router);
            persistence.load();
            PersistenceCommands.register(router, persistence);

            MemoryGuard guard = new MemoryGuard(store, MemoryConfig.defaults(), Clock.systemUTC(), new Random(),
                    persistence::recordEviction);
            router.setGuard(guard);
            MemoryCommands.register(router, guard, store);

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

    private PersistenceConfig noPersistence() {
        return new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0);
    }

    @Test // AC-3
    void noevictionRejectsWritesOverTheLimitButStillServesReads() throws Exception {
        try (RunningServer running = new RunningServer(noPersistence());
             TestClient client = new TestClient(running.port)) {
            assertEquals("OK", client.send("CONFIG", "SET", "maxmemory", "1"));
            assertEquals("OK", client.send("SET", "a", "1"));
            assertTrue(client.send("SET", "b", "2").startsWith("-OOM"));
            assertEquals("1", client.send("GET", "a"));
            assertEquals("PONG", client.send("PING"));
        }
    }

    @Test // AC-2
    void lruEvictionKeepsTheDatasetBounded() throws Exception {
        try (RunningServer running = new RunningServer(noPersistence());
             TestClient client = new TestClient(running.port)) {
            assertEquals("OK", client.send("CONFIG", "SET", "maxmemory-policy", "allkeys-lru"));
            assertEquals("OK", client.send("CONFIG", "SET", "maxmemory", "20kb"));

            for (int i = 0; i < 500; i++) {
                assertEquals("OK", client.send("SET", "key-" + i, "v".repeat(100)));
            }

            int keys = Integer.parseInt(client.send("DBSIZE"));
            assertTrue(keys > 0 && keys < 200, "keys: " + keys);
            String info = client.send("INFO");
            assertTrue(info.contains("evicted_keys:"), info);
            assertFalse(info.contains("evicted_keys:0"), info);
        }
    }

    @Test // AC-7: without the logged DEL a restart would bring every evicted key back
    void evictionsAreLoggedSoARestartDoesNotResurrectKeys() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, true, FsyncPolicy.ALWAYS, 0);
        int keysBeforeRestart;

        try (RunningServer first = new RunningServer(config);
             TestClient client = new TestClient(first.port)) {
            assertEquals("OK", client.send("CONFIG", "SET", "maxmemory-policy", "allkeys-lru"));
            assertEquals("OK", client.send("CONFIG", "SET", "maxmemory", "30kb"));
            for (int i = 0; i < 400; i++) {
                assertEquals("OK", client.send("SET", "key-" + i, "v".repeat(100)));
            }
            keysBeforeRestart = Integer.parseInt(client.send("DBSIZE"));
            assertTrue(keysBeforeRestart < 400, "keys: " + keysBeforeRestart);
        }

        try (RunningServer second = new RunningServer(config); // no limit configured: it just replays the log
             TestClient client = new TestClient(second.port)) {
            assertEquals(keysBeforeRestart, Integer.parseInt(client.send("DBSIZE")));
        }
    }
}