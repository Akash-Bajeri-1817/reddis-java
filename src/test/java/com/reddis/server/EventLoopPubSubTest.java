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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Pub/sub and transactions over real sockets: real subscribers receive pushed messages from the event loop. */
@Timeout(120)
class EventLoopPubSubTest {

    @TempDir
    Path dir;

    private TestServer server;

    @BeforeEach
    void start() throws IOException {
        server = new TestServer(new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test // AC-1, AC-2
    void aSubscriberReceivesMessagesPublishedByAnotherClient() throws Exception {
        try (TestClient subscriber = new TestClient(server.port);
             TestClient publisher = new TestClient(server.port)) {
            assertEquals("[subscribe,news,1]", subscriber.send("SUBSCRIBE", "news"));
            assertEquals("1", publisher.send("PUBLISH", "news", "hello"));
            assertEquals("[message,news,hello]", subscriber.receive());
        }
    }

    @Test // AC-2, AC-4
    void patternSubscriptionsReceiveMatchingChannels() throws Exception {
        try (TestClient subscriber = new TestClient(server.port);
             TestClient publisher = new TestClient(server.port)) {
            assertEquals("[psubscribe,news.*,1]", subscriber.send("PSUBSCRIBE", "news.*"));
            assertEquals("1", publisher.send("PUBLISH", "news.sports", "goal"));
            assertEquals("0", publisher.send("PUBLISH", "weather", "rain"));
            assertEquals("[pmessage,news.*,news.sports,goal]", subscriber.receive());
        }
    }

    @Test
    void publishingToNobodyReturnsZero() throws Exception {
        try (TestClient publisher = new TestClient(server.port)) {
            assertEquals("0", publisher.send("PUBLISH", "empty", "x"));
        }
    }

    @Test
    void everySubscriberReceivesTheMessage() throws Exception {
        try (TestClient a = new TestClient(server.port);
             TestClient b = new TestClient(server.port);
             TestClient publisher = new TestClient(server.port)) {
            a.send("SUBSCRIBE", "news");
            b.send("SUBSCRIBE", "news");
            assertEquals("2", publisher.send("PUBLISH", "news", "hi"));
            assertEquals("[message,news,hi]", a.receive());
            assertEquals("[message,news,hi]", b.receive());
        }
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        try (TestClient subscriber = new TestClient(server.port);
             TestClient publisher = new TestClient(server.port)) {
            subscriber.send("SUBSCRIBE", "news");
            assertEquals("[unsubscribe,news,0]", subscriber.send("UNSUBSCRIBE", "news"));
            assertEquals("0", publisher.send("PUBLISH", "news", "hi"));
            assertEquals("PONG", subscriber.send("PING")); // back in normal mode
        }
    }

    @Test // AC-5
    void aDisconnectedSubscriberIsForgotten() throws Exception {
        try (TestClient publisher = new TestClient(server.port)) {
            try (TestClient subscriber = new TestClient(server.port)) {
                subscriber.send("SUBSCRIBE", "news");
                assertEquals("1", publisher.send("PUBLISH", "news", "x")); // reaches the live subscriber
                subscriber.receive();
            } // the subscriber disconnects

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String receivers = publisher.send("PUBLISH", "news", "y");
            while (!receivers.equals("0") && System.nanoTime() < deadline) {
                Thread.sleep(20); // the server notices the disconnect on its next read event
                receivers = publisher.send("PUBLISH", "news", "y");
            }
            assertEquals("0", receivers);
        }
    }

    @Test // AC-3
    void subscriberModeRejectsOtherCommands() throws Exception {
        try (TestClient subscriber = new TestClient(server.port)) {
            subscriber.send("SUBSCRIBE", "news");
            assertTrue(subscriber.send("GET", "k").startsWith("-ERR Can't execute 'get'"));
            assertEquals("[pong,]", subscriber.send("PING"));
        }
    }

    @Test // AC-6
    void aSubscriberThatNeverReadsDoesNotBlockThePublisher() throws Exception {
        try (TestClient lazy = new TestClient(server.port);
             TestClient publisher = new TestClient(server.port)) {
            lazy.send("SUBSCRIBE", "big");
            String payload = "m".repeat(1_000_000);
            for (int i = 0; i < 10; i++) { // 10 MB pushed to a client that never reads
                assertEquals("1", publisher.send("PUBLISH", "big", payload));
            }
            assertEquals("PONG", publisher.send("PING")); // the loop is still responsive
        }
    }

    @Test // AC-7
    void aTransactionExecutesOverTheNetwork() throws Exception {
        try (TestClient client = new TestClient(server.port)) {
            assertEquals("OK", client.send("MULTI"));
            assertEquals("QUEUED", client.send("SET", "k", "v"));
            assertEquals("QUEUED", client.send("GET", "k"));
            assertEquals("[OK,v]", client.send("EXEC"));
        }
    }

    @Test // AC-9
    void aWatchConflictAbortsExecOverTheNetwork() throws Exception {
        try (TestClient first = new TestClient(server.port);
             TestClient second = new TestClient(server.port)) {
            assertEquals("OK", first.send("WATCH", "k"));
            assertEquals("OK", second.send("SET", "k", "theirs"));
            assertEquals("OK", first.send("MULTI"));
            assertEquals("QUEUED", first.send("SET", "k", "mine"));
            assertEquals("(nil)", first.send("EXEC")); // the null array
            assertEquals("theirs", first.send("GET", "k"));
        }
    }

    @Test // AC-7: no other client can ever observe half of a transaction
    void execIsAtomicWithRespectToOtherClients() throws Exception {
        int writers = 4;
        int readers = 4;
        int rounds = 300;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger torn = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            int id = w;
            futures.add(pool.submit(() -> {
                try (TestClient client = new TestClient(server.port)) {
                    go.await();
                    for (int i = 0; i < rounds; i++) {
                        String value = "w" + id + "-" + i;
                        assertEquals("OK", client.send("MULTI"));
                        assertEquals("QUEUED", client.send("SET", "k1", value));
                        assertEquals("QUEUED", client.send("SET", "k2", value)); // two separate commands
                        assertEquals("[OK,OK]", client.send("EXEC"));
                    }
                }
                return null;
            }));
        }
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try (TestClient client = new TestClient(server.port)) {
                    go.await();
                    for (int i = 0; i < rounds; i++) {
                        String reply = client.send("MGET", "k1", "k2"); // e.g. "[w1-7,w1-7]"
                        String[] pair = reply.substring(1, reply.length() - 1).split(",", -1);
                        assertEquals(2, pair.length);
                        if (!pair[0].equals(pair[1])) {
                            torn.incrementAndGet(); // saw k1 from one transaction and k2 from another
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
}