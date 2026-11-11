package com.reddis.demo;

import com.reddis.storage.DataStore;

import java.nio.charset.StandardCharsets;

/**
 * NOT a test. Compares the memory MODEL's estimate with the heap actually used, so you can see how close the
 * constants in MemoryModel are on your JVM (and adjust them if you like). Heap measurement is noisy: run it twice.
 */
public class MemoryModelDemo {

    public static void main(String[] args) {
        int entries = 300_000;
        long before = usedHeap();

        DataStore store = new DataStore();
        for (int i = 0; i < entries; i++) {
            store.set(String.format("key:%08d", i).getBytes(StandardCharsets.UTF_8), new byte[100]);
        }

        long after = usedHeap();
        long measured = after - before;
        System.out.printf("entries:           %d%n", entries);
        System.out.printf("model estimate:    %,d bytes (%.0f per entry)%n", store.usedMemory(), (double) store.usedMemory() / entries);
        System.out.printf("measured heap:     %,d bytes (%.0f per entry)%n", measured, (double) measured / entries);
        System.out.printf("estimate / actual: %.2f%n", (double) store.usedMemory() / measured);
        System.out.println(store.size()); // keeps the store reachable until here
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}