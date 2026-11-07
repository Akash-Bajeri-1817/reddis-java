package com.reddis.demo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * NOT a test. Run main() by hand to watch race conditions happen.
 * Results differ on every run and every machine; run it a few times.
 */
public class RaceConditionDemo {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 100_000;

    public static void main(String[] args) throws InterruptedException {
        experiment1HashMapLosesEntries();
        experiment2LostUpdates();
    }

    private static void experiment1HashMapLosesEntries() throws InterruptedException {
        System.out.println("=== Experiment 1: plain HashMap, 8 threads inserting DIFFERENT keys ===");
        Map<Integer, Integer> map = new HashMap<>();
        AtomicInteger crashes = new AtomicInteger();

        boolean finished = runInParallel(id -> {
            try {
                for (int i = 0; i < PER_THREAD; i++) {
                    map.put(id * PER_THREAD + i, i); // every key is unique, so size should be exact
                }
            } catch (RuntimeException e) {
                crashes.incrementAndGet();
            }
        });

        System.out.println("expected size  : " + THREADS * PER_THREAD);
        System.out.println("actual size    : " + map.size());
        System.out.println("threads crashed: " + crashes.get());
        if (!finished) {
            System.out.println("some threads never finished: stuck inside HashMap internals!");
        }
        System.out.println();
    }

    private static void experiment2LostUpdates() throws InterruptedException {
        System.out.println("=== Experiment 2: ConcurrentHashMap counter, 8 threads x 100000 increments ===");
        long expected = (long) THREADS * PER_THREAD;

        Map<String, Long> unsafe = new ConcurrentHashMap<>();
        unsafe.put("counter", 0L);
        runInParallel(id -> {
            for (int i = 0; i < PER_THREAD; i++) {
                // Each call is atomic, but read-then-write is TWO calls: updates get lost.
                unsafe.put("counter", unsafe.get("counter") + 1);
            }
        });
        System.out.println("get then put : " + unsafe.get("counter") + "   (expected " + expected + ")");

        Map<String, Long> safe = new ConcurrentHashMap<>();
        safe.put("counter", 0L);
        runInParallel(id -> {
            for (int i = 0; i < PER_THREAD; i++) {
                safe.merge("counter", 1L, Long::sum); // read-modify-write as ONE atomic operation
            }
        });
        System.out.println("merge        : " + safe.get("counter") + "   (expected " + expected + ")");
    }

    /** Runs the task on THREADS threads at once. @return true if all finished within 30 seconds each */
    private static boolean runInParallel(IntConsumer task) throws InterruptedException {
        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            threads[t] = new Thread(() -> task.accept(id));
            threads[t].setDaemon(true); // a stuck thread must not keep the JVM alive
        }
        for (Thread thread : threads) {
            thread.start();
        }
        boolean allFinished = true;
        for (Thread thread : threads) {
            thread.join(30_000);
            allFinished &= !thread.isAlive();
        }
        return allFinished;
    }
}