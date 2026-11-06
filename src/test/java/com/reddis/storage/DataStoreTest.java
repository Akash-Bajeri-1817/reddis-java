package com.reddis.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreTest {

    private DataStore store;

    @BeforeEach
    void setUp() {
        store = new DataStore(); // a fresh store per test keeps tests independent
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void setThenGetReturnsTheValue() {
        store.set(b("name"), b("arun"));
        assertArrayEquals(b("arun"), store.get(b("name")));
    }

    @Test
    void getOfMissingKeyReturnsNull() {
        assertNull(store.get(b("nothing")));
    }

    @Test
    void setOverwritesExistingValue() {
        store.set(b("k"), b("one"));
        store.set(b("k"), b("two"));
        assertArrayEquals(b("two"), store.get(b("k")));
        assertEquals(1, store.size());
    }

    @Test
    void lookupWorksWithADifferentArrayHoldingTheSameBytes() {
        store.set(new byte[]{5, 6, 7}, b("v"));
        assertArrayEquals(b("v"), store.get(new byte[]{5, 6, 7}));
    }

    @Test
    void deleteReportsWhetherTheKeyExisted() {
        store.set(b("k"), b("v"));
        assertTrue(store.delete(b("k")));
        assertFalse(store.delete(b("k")));
        assertNull(store.get(b("k")));
    }

    @Test
    void existsReflectsPresence() {
        assertFalse(store.exists(b("k")));
        store.set(b("k"), b("v"));
        assertTrue(store.exists(b("k")));
    }

    @Test
    void keysAreCaseSensitive() {
        store.set(b("Name"), b("upper"));
        assertNull(store.get(b("name")));
    }

    @Test
    void storesBinaryKeysAndValues() {
        byte[] key = {0, (byte) 255, '\r', '\n'};
        byte[] value = {(byte) 200, 0, '\r', '\n', 1};
        store.set(key, value);
        assertArrayEquals(value, store.get(new byte[]{0, (byte) 255, '\r', '\n'}));
    }
}