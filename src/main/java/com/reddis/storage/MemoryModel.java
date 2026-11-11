package com.reddis.storage;

/**
 * The memory model: how many bytes we ESTIMATE each structure to occupy on a 64-bit JVM with compressed pointers.
 * These are approximations (JVM flags change real sizes); what matters is that they are consistent and grow with
 * the data. Calibrate them with the MemoryModelDemo program if you want.
 */
public final class MemoryModel {

    private MemoryModel() { }

    /** A byte[]: 16-byte header plus the content rounded up to a multiple of 8. */
    public static long bytes(byte[] array) {
        return 16 + ((array.length + 7L) & ~7L);
    }

    /**
     * Fixed cost of one key in the main map: ConcurrentHashMap node + table slot + ByteArrayKey object + Entry object
     * (with its access metadata) + the two key indexes (about 64 bytes). The key's own bytes are added separately.
     */
    static final long ENTRY_OVERHEAD = 176;

    static final long STRING_OBJECT = 16;

    static final long LIST_BASE = 80;       // ListValue + ArrayDeque + initial array
    static final long LIST_ELEMENT = 8;     // the array slot (the byte[] itself is added separately)

    static final long HASH_BASE = 112;      // HashValue + LinkedHashMap + table
    static final long HASH_ENTRY = 72;      // map entry (40) + ByteArrayKey (24) + table slot (8)

    static final long SET_BASE = 112;
    static final long SET_ENTRY = 72;

    static final long ZSET_BASE = 600;      // HashMap + a skip list header with 32 levels
    static final long ZSET_ENTRY = 176;     // hash node (80) + skip list node with its arrays (96)
}