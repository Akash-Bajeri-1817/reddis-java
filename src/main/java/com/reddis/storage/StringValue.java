package com.reddis.storage;

/** A string value: raw bytes, never modified after creation. */
public record StringValue(byte[] bytes) implements RedisValue {

    @Override
    public String typeName() {
        return "string";
    }

    @Override
    public boolean isEmpty() {
        return false;
    }
}