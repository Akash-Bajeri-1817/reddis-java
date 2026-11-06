package com.reddis.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Wraps a byte[] so it can be used as a HashMap key.
 *
 * A raw byte[] cannot be a key: arrays use identity-based equals/hashCode, so two arrays
 * with identical contents would count as different keys. This class compares CONTENTS.
 *
 * The wrapped array must never be modified after construction. The hash is computed once
 * and cached; mutating the array would break the hashCode contract and "lose" the entry.
 */
public final class ByteArrayKey {

    private final byte[] bytes;
    private final int hash;

    public ByteArrayKey(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "key bytes must not be null");
        this.hash = Arrays.hashCode(bytes);
    }

    public byte[] bytes() {
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ByteArrayKey that)) {
            return false;
        }
        // Comparing the cached hashes first is a cheap way to reject most non-equal keys.
        return hash == that.hash && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}