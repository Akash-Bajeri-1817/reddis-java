package com.reddis.eviction;

import com.reddis.storage.DataStore;
import com.reddis.storage.KeySample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvictionPolicyTest {

    private static final long NOW = 10_000;

    private static KeySample sample(long lastAccess, int frequency, long expireAt) {
        return new KeySample(new byte[]{1}, lastAccess, frequency, expireAt);
    }

    @Test
    void lruPrefersTheLongestIdleKey() {
        KeySample recent = sample(9_000, 5, DataStore.NO_EXPIRY);
        KeySample old = sample(1_000, 5, DataStore.NO_EXPIRY);
        assertTrue(EvictionPolicy.ALLKEYS_LRU.score(old, NOW) > EvictionPolicy.ALLKEYS_LRU.score(recent, NOW));
        assertTrue(EvictionPolicy.VOLATILE_LRU.score(old, NOW) > EvictionPolicy.VOLATILE_LRU.score(recent, NOW));
    }

    @Test
    void lfuPrefersTheLeastFrequentlyUsedKey() {
        KeySample rare = sample(9_000, 3, DataStore.NO_EXPIRY);
        KeySample popular = sample(1_000, 200, DataStore.NO_EXPIRY); // older, but far more popular
        assertTrue(EvictionPolicy.ALLKEYS_LFU.score(rare, NOW) > EvictionPolicy.ALLKEYS_LFU.score(popular, NOW));
    }

    @Test
    void lfuBreaksTiesByIdleTime() {
        KeySample idle = sample(1_000, 5, DataStore.NO_EXPIRY);
        KeySample fresh = sample(9_000, 5, DataStore.NO_EXPIRY);
        assertTrue(EvictionPolicy.ALLKEYS_LFU.score(idle, NOW) > EvictionPolicy.ALLKEYS_LFU.score(fresh, NOW));
    }

    @Test
    void volatileTtlPrefersTheKeyClosestToItsDeadline() {
        KeySample soon = sample(5_000, 5, 20_000);
        KeySample later = sample(5_000, 5, 90_000);
        assertTrue(EvictionPolicy.VOLATILE_TTL.score(soon, NOW) > EvictionPolicy.VOLATILE_TTL.score(later, NOW));
    }

    @Test
    void randomPoliciesDoNotPreferAnyKey() {
        KeySample a = sample(1_000, 3, DataStore.NO_EXPIRY);
        KeySample b = sample(9_000, 200, DataStore.NO_EXPIRY);
        assertEquals(EvictionPolicy.ALLKEYS_RANDOM.score(a, NOW), EvictionPolicy.ALLKEYS_RANDOM.score(b, NOW));
    }

    @Test
    void namesRoundTripAndAreCaseInsensitive() {
        for (EvictionPolicy policy : EvictionPolicy.values()) {
            assertSame(policy, EvictionPolicy.parse(policy.redisName()));
        }
        assertSame(EvictionPolicy.ALLKEYS_LRU, EvictionPolicy.parse("ALLKEYS-LRU"));
        assertThrows(IllegalArgumentException.class, () -> EvictionPolicy.parse("sometimes-lru"));
    }

    @Test
    void thePolicyFlagsDescribeTheirCandidates() {
        assertFalse(EvictionPolicy.ALLKEYS_LRU.volatileOnly());
        assertTrue(EvictionPolicy.VOLATILE_LRU.volatileOnly());
        assertTrue(EvictionPolicy.VOLATILE_TTL.volatileOnly());
        assertTrue(EvictionPolicy.ALLKEYS_LFU.usesFrequency());
        assertFalse(EvictionPolicy.ALLKEYS_LRU.usesFrequency());
    }
}