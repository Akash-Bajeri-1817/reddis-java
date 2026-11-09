package com.reddis.server;

import com.reddis.command.CommandRouter;
import com.reddis.command.DelCommand;
import com.reddis.command.EchoCommand;
import com.reddis.command.ExistsCommand;
import com.reddis.command.GetCommand;
import com.reddis.command.MultiKeyCommands;
import com.reddis.command.PingCommand;
import com.reddis.command.SetCommand;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests: a real EventLoopServer on a real socket, driven by real TCP clients. */
@Timeout(120)
class EventLoopServerTest {

    private DataStore store;
    private EventLoopServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        store = new DataStore();
        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        MultiKeyCommands.register(router, store);
        router.register("BOOM", args -> {
            throw new IllegalStateException("boom"); // a buggy command
        });

        server = new EventLoopServer(0, router, store::purgeExpired, 20);
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(); // safe to call twice
    }

    // ---------------------------------------------------------------- helpers

    private Socket rawSocket() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(5_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String readExactly(Socket socket, int count) throws IOException {
        return new String(socket.getInputStream().readNBytes(count), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------ the basics (as before)

    @Test
    void answersPing() throws Exception {
        try (TestClient client = new TestClient(port)) {
            assertEquals("PONG", client.send("PING"));
        }
    }

    @Test // the Module 1 limitation, fixed in Module 3 with threads, now fixed WITHOUT threads
    void idleClientDoesNotBlockAnotherClient() throws Exception {
        try (TestClient idle = new TestClient(port);
             TestClient active = new TestClient(port)) {
            assertEquals("PONG", active.send("PING"));
        }
    }

    @Test
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
                    startSignal.await();
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
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(clients * opsPerClient, store.size());
    }

    @Test
    void dataWrittenByOneClientIsVisibleToAnother() throws Exception {
        try (TestClient writer = new TestClient(port);
             TestClient reader = new TestClient(port)) {
            assertEquals("OK", writer.send("SET", "shared", "hello"));
            assertEquals("hello", reader.send("GET", "shared"));
        }
    }

    @Test // AC-12
    void stoppingTheServerClosesClientConnections() throws Exception {
        try (TestClient client = new TestClient(port)) {
            assertEquals("PONG", client.send("PING"));
            server.stop();
            assertThrows(IOException.class, () -> client.send("PING"));
        }
    }

    // ------------------------------------------- fragmentation and pipelining

    @Test // AC-2
    void aCommandSplitAcrossPacketsIsReassembled() throws Exception {
        try (Socket socket = rawSocket()) {
            OutputStream out = socket.getOutputStream();
            out.write(bytes("*2\r\n$4\r\nECHO\r\n$5\r\nhel"));
            out.flush();
            Thread.sleep(100); // let the server see this fragment on its own first
            out.write(bytes("lo\r\n"));
            out.flush();

            assertEquals("$5\r\nhello\r\n", readExactly(socket, 11));
        }
    }

    @Test // AC-2
    void aCommandDeliveredOneByteAtATimeIsAnswered() throws Exception {
        try (Socket socket = rawSocket()) {
            OutputStream out = socket.getOutputStream();
            for (byte b : bytes("*1\r\n$4\r\nPING\r\n")) {
                out.write(b);
                out.flush();
            }
            assertEquals("+PONG\r\n", readExactly(socket, 7));
        }
    }

    @Test // AC-4
    void pipelinedCommandsAreAnsweredInOrder() throws Exception {
        StringBuilder commands = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            String message = "msg-" + i;
            commands.append("*2\r\n$4\r\nECHO\r\n$").append(message.length()).append("\r\n").append(message).append("\r\n");
            expected.append('$').append(message.length()).append("\r\n").append(message).append("\r\n");
        }

        try (Socket socket = rawSocket()) {
            socket.getOutputStream().write(bytes(commands.toString())); // one write, 1000 commands
            socket.getOutputStream().flush();
            assertEquals(expected.toString(), readExactly(socket, expected.length()));
        }
    }

    // ---------------------------------------------------- big values, slow clients

    @Test // AC-5: grows the input buffer, and the 5 MB reply needs partial writes
    void largeValuesRoundTripThroughPartialReadsAndWrites() throws Exception {
        String big = "x".repeat(5_000_000);
        try (TestClient client = new TestClient(port)) {
            assertEquals("OK", client.send("SET", "big", big));
            String reply = client.send("GET", "big");
            assertEquals(big.length(), reply.length());
            assertTrue(big.equals(reply));
        }
    }

    @Test // AC-6
    void aClientThatNeverReadsItsRepliesDoesNotBlockOthers() throws Exception {
        try (TestClient setup = new TestClient(port)) {
            assertEquals("OK", setup.send("SET", "big", "y".repeat(2_000_000)));
        }

        try (Socket slow = rawSocket();
             TestClient other = new TestClient(port)) {
            OutputStream out = slow.getOutputStream();
            for (int i = 0; i < 5; i++) {
                out.write(bytes("*2\r\n$3\r\nGET\r\n$3\r\nbig\r\n")); // 10 MB of replies that we never read
            }
            out.flush();

            assertEquals("PONG", other.send("PING"));
            assertEquals("OK", other.send("SET", "k", "v"));
        }
    }

    // ---------------------------------------------------------- misbehaving clients

    @Test // AC-8
    void aProtocolErrorClosesOnlyThatConnection() throws Exception {
        try (Socket bad = rawSocket();
             TestClient good = new TestClient(port)) {
            bad.getOutputStream().write(bytes("hello\r\n"));
            String reply = new String(bad.getInputStream().readAllBytes(), StandardCharsets.UTF_8); // until the server closes

            assertTrue(reply.startsWith("-ERR Protocol error"), reply);
            assertTrue(reply.endsWith("\r\n"));
            assertEquals("PONG", good.send("PING")); // the other client is unaffected
        }
    }

    @Test
    void unknownCommandsKeepTheConnectionOpen() throws Exception {
        try (TestClient client = new TestClient(port)) {
            assertEquals("-ERR unknown command 'FLY'", client.send("FLY"));
            assertEquals("PONG", client.send("PING"));
        }
    }

    @Test
    void aClientDisconnectingMidCommandDoesNotAffectTheServer() throws Exception {
        try (Socket flaky = rawSocket()) {
            flaky.getOutputStream().write(bytes("*2\r\n$4\r\nEC"));
            flaky.getOutputStream().flush();
        } // closed with half a command sent

        try (TestClient client = new TestClient(port)) {
            assertEquals("PONG", client.send("PING"));
        }
    }

    @Test // AC-9
    void aFailingCommandDoesNotKillTheServer() throws Exception {
        try (TestClient client = new TestClient(port)) {
            assertEquals("-ERR internal error", client.send("BOOM"));
            assertEquals("PONG", client.send("PING")); // the loop survived, on the same connection
        }
    }

    @Test
    void repliesAreFlushedWhenTheClientHalfClosesItsSide() throws Exception {
        try (Socket socket = rawSocket()) {
            socket.getOutputStream().write(bytes("*1\r\n$4\r\nPING\r\n"));
            socket.shutdownOutput(); // "I am done sending", but I still want my reply
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("+PONG\r\n", reply);
        }
    }

    // ----------------------------------------------- the single-thread payoffs

    @Test // AC-11: a reader must never see half of an MSET
    void multiKeyCommandsAreAtomic() throws Exception {
        int writers = 4;
        int readers = 4;
        int rounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger torn = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            int id = w;
            futures.add(pool.submit(() -> {
                try (TestClient client = new TestClient(port)) {
                    go.await();
                    for (int i = 0; i < rounds; i++) {
                        String value = "w" + id + "-" + i;
                        assertEquals("OK", client.send("MSET", "k1", value, "k2", value)); // both keys, same value
                    }
                }
                return null;
            }));
        }
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try (TestClient client = new TestClient(port)) {
                    go.await();
                    for (int i = 0; i < rounds; i++) {
                        String reply = client.send("MGET", "k1", "k2"); // e.g. "[w1-7,w1-7]"
                        String[] pair = reply.substring(1, reply.length() - 1).split(",", -1);
                        assertEquals(2, pair.length);
                        if (!pair[0].equals(pair[1])) {
                            torn.incrementAndGet(); // saw k1 from one MSET and k2 from another
                        }
                    }
                }
                return null;
            }));
        }

        go.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, torn.get());
    }

    @Test // AC-10
    void theEventLoopPurgesExpiredKeysThatNobodyReads() throws Exception {
        try (TestClient client = new TestClient(port)) {
            assertEquals("OK", client.send("SET", "temp", "1", "PX", "50"));
            assertEquals("OK", client.send("SET", "keep", "1"));
        }

        // Nobody ever reads "temp": only the periodic task running inside the loop can remove it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.size() > 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, store.size());
    }
}