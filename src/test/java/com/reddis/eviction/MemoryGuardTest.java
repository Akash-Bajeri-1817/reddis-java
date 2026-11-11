package com.reddis.eviction;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MemoryGuardTest {

    private static final byte[] VALUE = new byte[100];

    private MutableClock clock;
    private DataStore store;
    private final List<byte[]> evicted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        store = new DataStore(clock, new Random(5));
    }

    private MemoryGuard guard(long max, EvictionPolicy policy, int samples) {
        return new MemoryGuard(store, new MemoryConfig(max, policy, samples), clock, new Random(11), evicted::add);
    }

    private static byte[] key(int i) {
        return String.format("key-%04d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static int idOf(byte[] key) {
        return Integer.parseInt(new String(key, StandardCharsets.UTF_8).substring(4));
    }

    private void fill(int count) {
        for (int i = 0; i < count; i++) {
            store.set(key(i), VALUE);
        }
    }

    private static boolean isOom(RespValue reply) {
        return reply instanceof RespValue.RespError error && error.message().startsWith("OOM");
    }

    @Test
    void withoutALimitNothingIsEvicted() {
        fill(100);
        assertNull(guard(0, EvictionPolicy.ALLKEYS_LRU, 5).beforeExecute("SET"));
        assertEquals(100, store.size());
    }

    @Test
    void underTheLimitNothingHappens() {
        fill(100);
        assertNull(guard(store.usedMemory() * 2, EvictionPolicy.ALLKEYS_LRU, 5).beforeExecute("SET"));
        assertEquals(100, store.size());
    }

    @Test // AC-3
    void noevictionRejectsGrowingCommandsButNotOthers() {
        fill(100);
        MemoryGuard guard = guard(store.usedMemory() / 2, EvictionPolicy.NOEVICTION, 5);

        assertTrue(isOom(guard.beforeExecute("SET")));
        assertTrue(isOom(guard.beforeExecute("RPUSH")));
        assertNull(guard.beforeExecute("GET"));
        assertNull(guard.beforeExecute("DEL"));
        assertNull(guard.beforeExecute("EXPIRE"));
        assertEquals(100, store.size());
    }

    @Test // AC-2, AC-7
    void evictionFreesMemoryDownToTheLimitAndNotifiesTheListener() {
        fill(200);
        long limit = store.usedMemory() / 2;
        MemoryGuard guard = guard(limit, EvictionPolicy.ALLKEYS_LRU, 5);

        assertNull(guard.beforeExecute("SET"));

        assertTrue(store.usedMemory() <= limit);
        long evictions = guard.evictedKeys();
        assertTrue(evictions > 0);
        assertEquals(evictions, evicted.size());
        assertEquals(200 - evictions, store.size());
        for (byte[] key : evicted) {
            assertFalse(store.exists(key));
        }
    }

    @Test // AC-4
    void lruEvictsTheLeastRecentlyUsedKeys() {
        for (int i = 0; i < 1_000; i++) {
            clock.advance(1);
            store.set(key(i), VALUE);
        }
        clock.advance(10_000);
        for (int i = 500; i < 1_000; i++) {
            store.get(key(i)); // ids 500..999 are "hot"; 0..499 have been idle since they were created
        }

        long oneEntry = store.memoryUsage(key(0));
        MemoryGuard guard = guard(store.usedMemory() - 100 * oneEntry, EvictionPolicy.ALLKEYS_LRU, 10);
        assertNull(guard.beforeExecute("SET"));

        assertEquals(100, evicted.size());
        long coldEvicted = evicted.stream().filter(k -> idOf(k) < 500).count();
        assertTrue(coldEvicted >= 90, "cold keys evicted: " + coldEvicted + " of 100");
    }

    @Test // AC-4
    void lfuEvictsTheLeastFrequentlyUsedKeys() {
        fill(1_000);
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 500; i++) {
                store.get(key(i)); // ids 0..499 are used often
            }
        }

        long oneEntry = store.memoryUsage(key(0));
        MemoryGuard guard = guard(store.usedMemory() - 100 * oneEntry, EvictionPolicy.ALLKEYS_LFU, 10);
        assertNull(guard.beforeExecute("SET"));

        assertEquals(100, evicted.size());
        long rareEvicted = evicted.stream().filter(k -> idOf(k) >= 500).count();
        assertTrue(rareEvicted >= 90, "rarely used keys evicted: " + rareEvicted + " of 100");
    }

    @Test // AC-4
    void volatilePoliciesOnlyEvictKeysThatHaveATimeout() {
        for (int i = 0; i < 50; i++) {
            store.set(key(i), VALUE, 3_600_000); // ids 0..49 have a TTL
        }
        for (int i = 50; i < 100; i++) {
            store.set(key(i), VALUE);            // ids 50..99 do not
        }
        MemoryGuard guard = guard(store.usedMemory() * 3 / 4, EvictionPolicy.VOLATILE_LRU, 5);

        assertNull(guard.beforeExecute("SET"));

        assertTrue(guard.evictedKeys() > 0);
        for (byte[] key : evicted) {
            assertTrue(idOf(key) < 50, "evicted a key without a TTL: " + idOf(key));
        }
        for (int i = 50; i < 100; i++) {
            assertTrue(store.exists(key(i)));
        }
    }

    @Test
    void aVolatilePolicyCannotEvictWhenNoKeyHasATimeout() {
        fill(50);
        MemoryGuard guard = guard(store.usedMemory() / 2, EvictionPolicy.VOLATILE_LRU, 5);
        assertTrue(isOom(guard.beforeExecute("SET")));
        assertEquals(50, store.size());
    }

    @Test // AC-4
    void volatileTtlEvictsTheKeyClosestToItsDeadlineFirst() {
        for (int i = 0; i < 10; i++) {
            store.set(key(i), VALUE, (i + 1) * 1_000L); // key 0 expires first
        }
        MemoryGuard guard = guard(store.usedMemory() - 1, EvictionPolicy.VOLATILE_TTL, 20); // sees all 10 keys

        assertNull(guard.beforeExecute("SET"));

        assertEquals(1, evicted.size());
        assertEquals(0, idOf(evicted.get(0)));
    }

    @Test // AC-6
    void expiredKeysAreReclaimedWithoutCountingAsEvictions() {
        for (int i = 0; i < 100; i++) {
            store.set(key(i), VALUE, 100);
        }
        clock.advance(100); // all expired, none removed yet
        long limit = store.usedMemory() / 2;
        MemoryGuard guard = guard(limit, EvictionPolicy.ALLKEYS_LRU, 5);

        assertNull(guard.beforeExecute("SET"));

        assertTrue(store.usedMemory() <= limit);
        assertEquals(0, guard.evictedKeys());
        assertTrue(evicted.isEmpty()); // nothing to log: absolute deadlines make replay expire them anyway
    }

    @Test // AC-8
    void aLowerLimitSetAtRuntimeTakesEffectOnTheNextWrite() {
        MemoryGuard guard = guard(0, EvictionPolicy.ALLKEYS_LRU, 5);
        fill(100);
        assertNull(guard.beforeExecute("SET"));
        assertEquals(100, store.size());

        guard.setMaxMemory(store.usedMemory() / 2);
        assertNull(guard.beforeExecute("SET"));

        assertTrue(store.usedMemory() <= guard.maxMemory());
        assertTrue(store.size() < 100);
    }
}