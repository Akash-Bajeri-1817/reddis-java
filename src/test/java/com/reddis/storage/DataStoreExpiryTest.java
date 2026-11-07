package com.reddis.storage;

import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreExpiryTest {

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
    void keyWithoutExpiryNeverExpires() {
        store.set(b("k"), b("v"));
        clock.advance(10_000_000_000L);
        assertArrayEquals(b("v"), store.get(b("k")));
        assertEquals(DataStore.TTL_NO_EXPIRY, store.ttlMillis(b("k")));
    }

    @Test
    void keyLivesUntilItsDeadlineAndThenDisappears() {
        store.set(b("k"), b("v"), 1_000);
        clock.advance(999);
        assertArrayEquals(b("v"), store.get(b("k")));
        clock.advance(1); // exactly at the deadline: expired
        assertNull(store.get(b("k")));
    }

    @Test
    void expiredKeyIsInvisibleToExistsAndDelete() {
        store.set(b("k"), b("v"), 100);
        clock.advance(100);
        assertFalse(store.exists(b("k")));
        assertFalse(store.delete(b("k")));
    }

    @Test
    void ttlReportsRemainingMillis() {
        store.set(b("k"), b("v"), 5_000);
        clock.advance(1_200);
        assertEquals(3_800, store.ttlMillis(b("k")));
    }

    @Test
    void ttlOfMissingOrExpiredKeyIsMinusTwo() {
        assertEquals(DataStore.TTL_MISSING, store.ttlMillis(b("nope")));
        store.set(b("k"), b("v"), 10);
        clock.advance(10);
        assertEquals(DataStore.TTL_MISSING, store.ttlMillis(b("k")));
    }

    @Test
    void plainSetClearsAnExistingExpiry() {
        store.set(b("k"), b("v1"), 1_000);
        store.set(b("k"), b("v2"));
        clock.advance(5_000);
        assertArrayEquals(b("v2"), store.get(b("k")));
        assertEquals(DataStore.TTL_NO_EXPIRY, store.ttlMillis(b("k")));
    }

    @Test
    void expireAddsATimeoutToAnExistingKey() {
        store.set(b("k"), b("v"));
        assertTrue(store.expire(b("k"), 2_000));
        assertEquals(2_000, store.ttlMillis(b("k")));
        assertFalse(store.expire(b("missing"), 2_000));
    }

    @Test
    void expireWithANonPositiveTtlDeletesTheKey() {
        store.set(b("a"), b("v"));
        store.set(b("b"), b("v"));
        assertTrue(store.expire(b("a"), 0));
        assertTrue(store.expire(b("b"), -5));
        assertNull(store.get(b("a")));
        assertNull(store.get(b("b")));
        assertEquals(0, store.size());
    }

    @Test
    void expireOnAnAlreadyExpiredKeyReturnsFalse() {
        store.set(b("k"), b("v"), 100);
        clock.advance(100);
        assertFalse(store.expire(b("k"), 5_000));
        assertNull(store.get(b("k")));
    }

    @Test
    void persistRemovesTheTimeout() {
        store.set(b("k"), b("v"), 1_000);
        assertTrue(store.persist(b("k")));
        assertEquals(DataStore.TTL_NO_EXPIRY, store.ttlMillis(b("k")));
        clock.advance(5_000);
        assertArrayEquals(b("v"), store.get(b("k")));
        assertFalse(store.persist(b("k"))); // nothing left to remove
    }

    @Test
    void persistOnAKeyWithoutTimeoutOrOnAMissingKeyReturnsFalse() {
        store.set(b("k"), b("v"));
        assertFalse(store.persist(b("k")));
        assertFalse(store.persist(b("missing")));
    }

    @Test
    void lazyExpirationFreesTheEntryOnAccess() {
        store.set(b("k"), b("v"), 100);
        assertEquals(1, store.size());
        clock.advance(100);
        assertNull(store.get(b("k"))); // this access performs the removal
        assertEquals(0, store.size());
    }

    @Test
    void purgeRemovesExpiredKeysWithoutAnyAccess() {
        store.set(b("a"), b("1"), 100);
        store.set(b("b"), b("2"), 100);
        store.set(b("c"), b("3"), 100_000);

        assertEquals(0, store.purgeExpired()); // nothing is due yet
        clock.advance(200);

        assertEquals(2, store.purgeExpired());
        assertEquals(1, store.size());
        assertArrayEquals(b("3"), store.get(b("c")));
    }

    @Test // AC-8: the first queue item becomes stale when the timeout is extended
    void purgeLeavesKeysWhoseTimeoutWasExtended() {
        store.set(b("k"), b("v"), 100);
        assertTrue(store.expire(b("k"), 100_000));
        clock.advance(200);

        assertEquals(0, store.purgeExpired()); // the stale 100ms item fires but must not delete the key
        assertArrayEquals(b("v"), store.get(b("k")));
    }

    @Test // AC-8
    void purgeLeavesKeysWhoseTimeoutWasRemoved() {
        store.set(b("k"), b("v"), 100);
        assertTrue(store.persist(b("k")));
        clock.advance(200);

        assertEquals(0, store.purgeExpired());
        assertArrayEquals(b("v"), store.get(b("k")));
    }

    @Test // AC-8: the fresh value must survive a stale expiry event
    void purgeDoesNotDeleteAFreshValueWrittenAfterExpiry() {
        store.set(b("k"), b("old"), 100);
        clock.advance(200);          // "old" is now logically expired but still stored
        store.set(b("k"), b("new")); // nobody read it in between

        assertEquals(0, store.purgeExpired());
        assertArrayEquals(b("new"), store.get(b("k")));
    }

    @Test
    void hugeTtlSaturatesInsteadOfOverflowing() {
        store.set(b("k"), b("v"), Long.MAX_VALUE);
        clock.advance(1_000_000_000_000L);
        assertArrayEquals(b("v"), store.get(b("k")));
        assertTrue(store.ttlMillis(b("k")) > 0);
    }

    @Test
    void nonPositiveTtlInSetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.set(b("k"), b("v"), 0));
        assertThrows(IllegalArgumentException.class, () -> store.set(b("k"), b("v"), -1));
    }
}