package com.reddis.eviction;

import com.reddis.storage.KeySample;

import java.util.Locale;

/**
 * The eviction policies, written as the STRATEGY pattern: each constant overrides score(), "how good a victim is
 * this sampled key?" (higher = evict it first). The guard just picks the best score among the sampled keys.
 */
public enum EvictionPolicy {

    /** Never evict: growing commands are rejected with -OOM when memory is full. */
    NOEVICTION("noeviction", false, false) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return 0;
        }
    },

    /** Evict the key that has been idle the longest. */
    ALLKEYS_LRU("allkeys-lru", false, false) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return idleMillis(key, nowMillis);
        }
    },

    VOLATILE_LRU("volatile-lru", true, false) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return idleMillis(key, nowMillis);
        }
    },

    /** Evict the least frequently used key (idle time breaks ties). */
    ALLKEYS_LFU("allkeys-lfu", false, true) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return lfuScore(key, nowMillis);
        }
    },

    VOLATILE_LFU("volatile-lfu", true, true) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return lfuScore(key, nowMillis);
        }
    },

    /** Every candidate is equally good: the random sample itself is the choice. */
    ALLKEYS_RANDOM("allkeys-random", false, false) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return 0;
        }
    },

    VOLATILE_RANDOM("volatile-random", true, false) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return 0;
        }
    },

    /** Evict the key whose deadline is closest. */
    VOLATILE_TTL("volatile-ttl", true, false) {
        @Override
        public double score(KeySample key, long nowMillis) {
            return -(double) key.expireAtMillis(); // an earlier deadline gives a larger score
        }
    };

    private static final int LFU_MAX = 255;

    private final String redisName;
    private final boolean volatileOnly;
    private final boolean frequencyBased;

    EvictionPolicy(String redisName, boolean volatileOnly, boolean frequencyBased) {
        this.redisName = redisName;
        this.volatileOnly = volatileOnly;
        this.frequencyBased = frequencyBased;
    }

    /** Higher means "evict this one first". */
    public abstract double score(KeySample key, long nowMillis);

    /** The name used by CONFIG and the command line, e.g. "allkeys-lru". */
    public String redisName() {
        return redisName;
    }

    /** true if only keys that have a TTL may be evicted. */
    public boolean volatileOnly() {
        return volatileOnly;
    }

    /** true if the policy uses the LFU counter (so OBJECT FREQ is meaningful). */
    public boolean usesFrequency() {
        return frequencyBased;
    }

    public static EvictionPolicy parse(String name) {
        for (EvictionPolicy policy : values()) {
            if (policy.redisName.equals(name.toLowerCase(Locale.ROOT))) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unknown maxmemory-policy: " + name);
    }

    private static double idleMillis(KeySample key, long nowMillis) {
        return Math.max(0, nowMillis - key.lastAccessMillis());
    }

    /** Lower frequency dominates; among equal frequencies the longer-idle key is the better victim. */
    private static double lfuScore(KeySample key, long nowMillis) {
        return (LFU_MAX - key.frequency()) * 1e12 + idleMillis(key, nowMillis);
    }
}