package com.reddis.storage;

/**
 * What the eviction policies get to see about a sampled key.
 *
 * @param lastAccessMillis when the key was last read or written (for LRU)
 * @param frequency        the LFU counter 0..255, already decayed to "now" (for LFU)
 * @param expireAtMillis   absolute deadline, or DataStore.NO_EXPIRY (for volatile-ttl)
 */
public record KeySample(byte[] key, long lastAccessMillis, int frequency, long expireAtMillis) { }