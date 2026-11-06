package com.reddis.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ByteArrayKeyTest {

    @Test
    void keysWithSameContentAreEqualEvenIfArraysAreDifferentObjects() {
        ByteArrayKey a = new ByteArrayKey(new byte[]{1, 2, 3});
        ByteArrayKey b = new ByteArrayKey(new byte[]{1, 2, 3});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void keysWithDifferentContentAreNotEqual() {
        assertNotEquals(new ByteArrayKey(new byte[]{1, 2, 3}), new ByteArrayKey(new byte[]{1, 2, 4}));
    }

    @Test
    void wrappedKeyWorksInAHashMap() {
        Map<ByteArrayKey, String> map = new HashMap<>();
        map.put(new ByteArrayKey(new byte[]{1, 2}), "hello");
        assertEquals("hello", map.get(new ByteArrayKey(new byte[]{1, 2})));
    }

    /** A "learning test": it documents WHY ByteArrayKey exists. */
    @Test
    void rawByteArrayDoesNotWorkAsAMapKey() {
        Map<byte[], String> map = new HashMap<>();
        map.put(new byte[]{1, 2}, "hello");
        assertNull(map.get(new byte[]{1, 2})); // different array object => treated as a different key
    }
}