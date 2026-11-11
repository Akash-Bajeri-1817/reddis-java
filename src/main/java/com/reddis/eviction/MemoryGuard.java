package com.reddis.eviction;

import com.reddis.command.CommandGuard;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.KeySample;

import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Enforces maxmemory. Before a command that can GROW memory runs, it evicts keys until the dataset fits (or reports
 * -OOM). Like Redis it picks victims by SAMPLING: look at a few random keys and evict the best according to the
 * policy. The command itself may then overshoot the limit by its own size; the next one evicts again.
 *
 * Runs on the thread that executes commands (the event loop).
 */
public final class MemoryGuard implements CommandGuard {

    /** Commands that can increase memory use. Reads, DEL, pops and TTL changes are always allowed. */
    private static final Set<String> GROWS_MEMORY =
            Set.of("SET", "MSET", "LPUSH", "RPUSH", "HSET", "SADD", "ZADD", "ZINCRBY");

    private static final RespValue OOM =
            new RespValue.RespError("OOM command not allowed when used memory > 'maxmemory'.");

    /** One command never does more than this many evictions (Redis bounds the time instead). */
    private static final int MAX_EVICTIONS_PER_COMMAND = 100_000;

    private final DataStore store;
    private final Clock clock;
    private final Random random;
    private final Consumer<byte[]> evictionListener;

    private volatile long maxMemory;
    private volatile EvictionPolicy policy;
    private volatile int samples;
    private final AtomicLong evictedKeys = new AtomicLong();

    /**
     * @param clock            must be the same clock the DataStore uses
     * @param evictionListener told about every real eviction (the AOF logs it as DEL)
     */
    public MemoryGuard(DataStore store, MemoryConfig config, Clock clock, Random random,
                       Consumer<byte[]> evictionListener) {
        this.store = store;
        this.clock = clock;
        this.random = random;
        this.evictionListener = evictionListener;
        this.maxMemory = config.maxMemoryBytes();
        this.policy = config.policy();
        this.samples = config.samples();
    }

    @Override
    public RespValue beforeExecute(String commandName) {
        long limit = maxMemory;
        if (limit <= 0 || !GROWS_MEMORY.contains(commandName)) {
            return null;
        }
        if (store.usedMemory() <= limit) {
            return null;
        }
        return evictUntilWithin(limit) ? null : OOM;
    }

    /** @return true if memory is now within the limit */
    private boolean evictUntilWithin(long limit) {
        EvictionPolicy current = policy;
        if (current == EvictionPolicy.NOEVICTION) {
            return false;
        }

        for (int i = 0; i < MAX_EVICTIONS_PER_COMMAND && store.usedMemory() > limit; i++) {
            List<KeySample> candidates = store.sample(samples, current.volatileOnly(), random);
            if (candidates.isEmpty()) {
                return false; // nothing evictable, e.g. a volatile-* policy while no key has a TTL
            }

            long now = clock.millis();
            KeySample victim = null;
            boolean victimExpired = false;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (KeySample candidate : candidates) {
                boolean expired = candidate.expireAtMillis() != DataStore.NO_EXPIRY
                        && now >= candidate.expireAtMillis();
                double score = expired ? Double.POSITIVE_INFINITY : current.score(candidate, now); // free memory first
                if (victim == null || score > bestScore) {
                    victim = candidate;
                    bestScore = score;
                    victimExpired = expired;
                }
            }

            if (store.evict(victim.key()) && !victimExpired) {
                evictedKeys.incrementAndGet();
                evictionListener.accept(victim.key()); // an expired key is not an eviction and needs no DEL
            }
        }
        return store.usedMemory() <= limit;
    }

    // ------------------------------------------------------------ runtime config

    public long maxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("maxmemory must not be negative");
        }
        this.maxMemory = bytes;
    }

    public EvictionPolicy policy() {
        return policy;
    }

    public void setPolicy(EvictionPolicy policy) {
        this.policy = policy;
    }

    public int samples() {
        return samples;
    }

    public void setSamples(int samples) {
        if (samples < 1) {
            throw new IllegalArgumentException("maxmemory-samples must be at least 1");
        }
        this.samples = samples;
    }

    public long evictedKeys() {
        return evictedKeys.get();
    }

    public Clock clock() {
        return clock;
    }
}