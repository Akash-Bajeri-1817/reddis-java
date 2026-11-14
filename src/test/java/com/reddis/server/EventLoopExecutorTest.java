package com.reddis.server;

import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.PersistenceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(30)
class EventLoopExecutorTest {

    @TempDir
    Path dir;

    @Test
    void tasksRunOnTheEventLoopThread() throws Exception {
        try (TestServer server = new TestServer(new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0))) {
            CompletableFuture<String> threadName = new CompletableFuture<>();
            server.server.execute(() -> threadName.complete(Thread.currentThread().getName()));
            assertEquals("reddis-event-loop", threadName.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void tasksRunInSubmissionOrder() throws Exception {
        try (TestServer server = new TestServer(new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0))) {
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(50);
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int n = i;
                expected.add(n);
                server.server.execute(() -> {
                    order.add(n);
                    done.countDown();
                });
            }
            done.await(5, TimeUnit.SECONDS);
            assertEquals(expected, order);
        }
    }
}