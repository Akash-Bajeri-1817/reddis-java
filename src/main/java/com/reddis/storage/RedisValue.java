package com.reddis.storage;

/** Anything that can be stored under a key. */
public interface RedisValue {

    /** The name TYPE reports: "string", "list", "hash", "set", ... */
    String typeName();

    /** Empty collections are never kept in the store (like Redis). */
    boolean isEmpty();
}