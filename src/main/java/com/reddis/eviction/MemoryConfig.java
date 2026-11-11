package com.reddis.eviction;

import java.util.Locale;
import java.util.Objects;

/**
 * @param maxMemoryBytes limit on the estimated memory used by the dataset; 0 = unlimited
 * @param policy         what to do when the limit is reached
 * @param samples        how many random keys to look at per eviction (Redis default: 5)
 */
public record MemoryConfig(long maxMemoryBytes, EvictionPolicy policy, int samples) {

    public MemoryConfig {
        if (maxMemoryBytes < 0) {
            throw new IllegalArgumentException("maxmemory must not be negative");
        }
        if (samples < 1) {
            throw new IllegalArgumentException("maxmemory-samples must be at least 1");
        }
        Objects.requireNonNull(policy);
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(0, EvictionPolicy.NOEVICTION, 5);
    }

    /** Parses "1024", "100kb", "100mb", "2gb" or "512b" (binary multiples, case-insensitive). */
    public static long parseBytes(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (value.endsWith("gb")) {
            multiplier = 1L << 30;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("mb")) {
            multiplier = 1L << 20;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("kb")) {
            multiplier = 1L << 10;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("b")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            long amount = Long.parseLong(value);
            if (amount < 0) {
                throw new IllegalArgumentException("memory size must not be negative: " + text);
            }
            return Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("invalid memory size: " + text);
        }
    }
}