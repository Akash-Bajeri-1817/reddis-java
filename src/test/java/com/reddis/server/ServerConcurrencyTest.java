package com.reddis.server;

import com.reddis.command.CommandRouter;
import com.reddis.command.GetCommand;
import com.reddis.command.PingCommand;
import com.reddis.command.SetCommand;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Integration tests: a real Server on a real socket, talked to by real TCP clients. */
@Timeout(60)
class ServerConcurrencyTest {

    private DataStore store;
    private Server server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        store = new DataStore();
        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));

        server = new Server(0, router, Executors.newVirtualThreadPerTaskExecutor()); // port 0: any free port
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(); // safe to call twice
    }

    @Test // AC-1: this is the Module 1 limitation, now fixed
    void idleClientDoesNotBlockAnotherClient() throws Exception {
        try (TestClient idle = new TestClient(port);
             TestClient active = new TestClient(port)) {
            // 'idle' is connected but silent. In Module 1 this froze 'active' forever.
            assertEquals("PONG", active.send("PING"));
        }
    }

    @Test // AC-2
    void manyClientsCanWorkAtTheSameTime() throws Exception {
        int clients = 16;
        int opsPerClient = 200;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int c = 0; c < clients; c++) {
            int clientId = c;
            futures.add(pool.submit(() -> {
                try (TestClient client = new TestClient(port)) {
                    startSignal.await(); // every client starts hammering at the same moment
                    for (int i = 0; i < opsPerClient; i++) {
                        String key = "key-" + clientId + "-" + i;
                        String value = "value-" + clientId + "-" + i;
                        assertEquals("OK", client.send("SET", key, value));
                        assertEquals(value, client.send("GET", key));
                    }
                }
                return null;
            }));
        }

        startSignal.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS); // rethrows any assertion failure from a worker thread
        }
        pool.shutdown();

        assertEquals(clients * opsPerClient, store.size()); // no write was lost
    }

    @Test // AC-3
    void dataWrittenByOneClientIsVisibleToAnother() throws Exception {
        try (TestClient writer = new TestClient(port);
             TestClient reader = new TestClient(port)) {
            assertEquals("OK", writer.send("SET", "shared", "hello"));
            assertEquals("hello", reader.send("GET", "shared"));
        }
    }

    @Test // AC-5
    void stoppingTheServerClosesClientConnections() throws Exception {
        try (TestClient client = new TestClient(port)) {
            assertEquals("PONG", client.send("PING"));
            server.stop();
            assertThrows(IOException.class, () -> client.send("PING"));
        }
    }
}