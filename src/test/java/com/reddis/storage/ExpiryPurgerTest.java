package com.reddis.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpiryPurgerTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test // AC-7
    @Timeout(15)
    void backgroundThreadRemovesExpiredKeysThatNobodyReads() throws Exception {
        DataStore store = new DataStore(); // real clock

        try (ExpiryPurger purger = new ExpiryPurger(store, 20)) {
            purger.start();
            store.set(b("a"), b("1"), 50);
            store.set(b("b"), b("2"), 50);
            store.set(b("keep"), b("3"));
            assertEquals(3, store.size());

            // We never call get() on a or b, so only the background purger can remove them.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (store.size() > 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, store.size());
        }
    }
}