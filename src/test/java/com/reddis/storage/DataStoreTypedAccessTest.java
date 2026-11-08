package com.reddis.storage;

import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreTypedAccessTest {

    private MutableClock clock;
    private DataStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        store = new DataStore(clock);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private int pushRight(byte[] key, byte[] value) {
        return store.access(key, ListValue.class, ListValue::new, list -> {
            list.pushRight(value);
            return list.size();
        });
    }

    private int listSize(byte[] key) {
        return store.access(key, ListValue.class, ListValue::new, ListValue::size);
    }

    @Test
    void accessCreatesTheCollectionOnFirstWrite() {
        assertEquals(1, pushRight(b("k"), b("a")));
        assertEquals("list", store.type(b("k")));
        assertEquals(1, store.size());
    }

    @Test
    void readOnlyAccessToAMissingKeyCreatesNothing() {
        assertEquals(0, listSize(b("k")));
        assertEquals(0, store.size());
        assertEquals("none", store.type(b("k")));
    }

    @Test
    void accessOnAKeyOfAnotherTypeThrowsWrongTypeAndChangesNothing() {
        store.set(b("s"), b("v"));
        assertThrows(WrongTypeException.class, () -> pushRight(b("s"), b("x")));
        assertArrayEquals(b("v"), store.get(b("s")));
    }

    @Test
    void getOnACollectionThrowsWrongType() {
        pushRight(b("l"), b("x"));
        assertThrows(WrongTypeException.class, () -> store.get(b("l")));
    }

    @Test
    void anEmptiedCollectionIsRemovedAutomatically() {
        pushRight(b("k"), b("a"));
        byte[] popped = store.access(b("k"), ListValue.class, ListValue::new, ListValue::popLeft);
        assertArrayEquals(b("a"), popped);
        assertFalse(store.exists(b("k")));
        assertEquals(0, store.size());
    }

    @Test
    void modifyingACollectionKeepsItsTimeout() {
        pushRight(b("k"), b("a"));
        assertTrue(store.expire(b("k"), 5_000));
        clock.advance(1_000);
        pushRight(b("k"), b("b"));
        assertEquals(4_000, store.ttlMillis(b("k")));
    }

    @Test
    void anExpiredCollectionIsTreatedAsMissingAndRestartsWithoutTimeout() {
        pushRight(b("k"), b("a"));
        store.expire(b("k"), 100);
        clock.advance(100);

        assertEquals(0, listSize(b("k")));   // reads see an empty list...
        assertEquals(0, store.size());       // ...and the dead entry was cleaned up

        assertEquals(1, pushRight(b("k"), b("fresh")));
        assertEquals(DataStore.TTL_NO_EXPIRY, store.ttlMillis(b("k")));
    }

    @Test
    void setOverwritesACollection() {
        pushRight(b("k"), b("a"));
        store.set(b("k"), b("v"));
        assertArrayEquals(b("v"), store.get(b("k")));
        assertEquals("string", store.type(b("k")));
    }

    @Test
    void expireAndDeleteWorkOnCollections() {
        pushRight(b("k"), b("a"));
        assertTrue(store.expire(b("k"), 1_000));
        assertTrue(store.delete(b("k")));
        assertEquals(0, store.size());
    }

    @Test
    void typeReportsTheKindOfValue() {
        store.set(b("s"), b("v"));
        pushRight(b("l"), b("x"));
        store.access(b("h"), HashValue.class, HashValue::new, h -> h.put(b("f"), b("v")));
        store.access(b("st"), SetValue.class, SetValue::new, s -> s.add(b("m")));

        assertEquals("string", store.type(b("s")));
        assertEquals("list", store.type(b("l")));
        assertEquals("hash", store.type(b("h")));
        assertEquals("set", store.type(b("st")));
        assertEquals("none", store.type(b("nothing")));
    }

    @Test // AC-7: ArrayDeque is not thread-safe; access() must make concurrent pushes safe
    @Timeout(30)
    void concurrentPushesToOneListAreAllKept() throws Exception {
        int threads = 16;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    pushRight(b("list"), b("x"));
                }
                return null;
            }));
        }

        go.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertEquals(threads * perThread, listSize(b("list")));
    }
}