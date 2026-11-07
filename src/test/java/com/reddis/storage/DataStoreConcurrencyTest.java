package com.reddis.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataStoreConcurrencyTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test // AC-4
    @Timeout(30)
    void concurrentWritesToDifferentKeysAreAllKept() throws Exception {
        DataStore store = new DataStore();
        int threads = 8;
        int keysPerThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int id = t;
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < keysPerThread; i++) {
                    store.set(b("k-" + id + "-" + i), b("v"));
                }
                return null;
            }));
        }

        go.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertEquals(threads * keysPerThread, store.size());
    }

    @Test // AC-4
    @Timeout(30)
    void concurrentDeletesOfOneKeySucceedExactlyOnce() throws Exception {
        DataStore store = new DataStore();
        store.set(b("k"), b("v"));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                return store.delete(b("k"));
            }));
        }

        go.countDown();
        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successes++;
            }
        }
        pool.shutdown();

        assertEquals(1, successes); // sixteen racers, exactly one winner
    }
}