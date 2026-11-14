package com.reddis.storage;

import com.reddis.support.StoreDump;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreClearTest {

    @Test
    void clearRemovesEverythingAndKeepsTheBookkeepingConsistent() {
        DataStore store = new DataStore();
        StoreDump.populate(store); // every type, a key with a deadline, a binary key
        assertTrue(store.size() > 0);

        store.clear();

        assertEquals(0, store.size());
        assertEquals(0, store.usedMemory());
        store.validate(); // memory counter and both key indexes agree with the (empty) map
    }

    @Test
    void theStoreIsUsableAfterClearAndClearingAnEmptyStoreIsHarmless() {
        DataStore store = new DataStore();
        store.clear();
        store.set("k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8));
        store.clear();
        store.set("k2".getBytes(StandardCharsets.UTF_8), "v2".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, store.size());
        store.validate();
    }
}