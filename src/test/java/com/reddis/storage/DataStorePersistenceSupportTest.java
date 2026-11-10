package com.reddis.storage;

import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class DataStorePersistenceSupportTest {

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

    @Test
    void expireAtSetsAnAbsoluteDeadline() {
        store.set(b("k"), b("v"));
        assertTrue(store.expireAt(b("k"), 1_005_000));
        assertEquals(5_000, store.ttlMillis(b("k")));
        assertFalse(store.expireAt(b("missing"), 1_005_000));
    }

    @Test
    void expireAtWithADeadlineThatIsNotInTheFutureDeletesTheKey() {
        store.set(b("k"), b("v"));
        assertTrue(store.expireAt(b("k"), 999_999));
        assertEquals(0, store.size());
    }

    @Test
    void expireAtMillisReportsTheAbsoluteDeadline() {
        assertEquals(DataStore.TTL_MISSING, store.expireAtMillis(b("k")));
        store.set(b("k"), b("v"));
        assertEquals(DataStore.NO_EXPIRY, store.expireAtMillis(b("k")));
        store.set(b("t"), b("v"), 7_000);
        assertEquals(1_007_000, store.expireAtMillis(b("t")));
    }

    @Test
    void restoreInsertsAValueWithItsDeadline() {
        store.restore(b("k"), new StringValue(b("v")), 1_001_000);
        assertArrayEquals(b("v"), store.get(b("k")));
        clock.advance(1_000);
        assertNull(store.get(b("k")));
    }

    @Test
    void restoredKeysArePurgedActivelyToo() {
        store.restore(b("k"), new StringValue(b("v")), 1_001_000);
        clock.advance(1_000);
        assertEquals(1, store.purgeExpired());
    }

    @Test
    void restoreIgnoresEntriesThatAreAlreadyExpired() {
        store.restore(b("k"), new StringValue(b("v")), 999_000);
        assertEquals(0, store.size());
    }

    @Test
    void restoreIgnoresEmptyCollections() {
        store.restore(b("k"), new ListValue(), DataStore.NO_EXPIRY);
        assertEquals(0, store.size());
    }

    @Test
    void forEachEntrySkipsExpiredEntriesAndReportsDeadlines() throws Exception {
        store.set(b("a"), b("1"));
        store.set(b("b"), b("2"), 100);
        store.set(b("c"), b("3"), 5_000);
        clock.advance(100); // b has expired

        Map<String, Long> seen = new TreeMap<>();
        store.forEachEntry((key, value, expireAt) -> seen.put(new String(key, StandardCharsets.UTF_8), expireAt));

        assertEquals(Map.of("a", DataStore.NO_EXPIRY, "c", 1_005_000L), seen);
    }
}