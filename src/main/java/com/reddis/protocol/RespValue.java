package com.reddis.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The five RESP2 data types, modelled as a sealed interface.
 * "Sealed" means only the records below can implement it, so a switch over
 * RespValue is checked for completeness by the compiler.
 */
public sealed interface RespValue {

    /** +OK\r\n */
    record SimpleString(String value) implements RespValue { }

    /** -ERR something went wrong\r\n */
    record RespError(String message) implements RespValue { }

    /** :42\r\n */
    record RespInteger(long value) implements RespValue { }

    /** $5\r\nhello\r\n  ($-1\r\n when data is null). Binary-safe: holds raw bytes. */
    record BulkString(byte[] data) implements RespValue {
        public static final BulkString NULL = new BulkString(null);

        public static BulkString of(String text) {
            return new BulkString(text.getBytes(StandardCharsets.UTF_8));
        }

        public boolean isNull() {
            return data == null;
        }
    }

    /** *2\r\n...elements... */
    record RespArray(List<RespValue> elements) implements RespValue { }
}