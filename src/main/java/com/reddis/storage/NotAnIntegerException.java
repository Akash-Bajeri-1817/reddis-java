package com.reddis.storage;

/** A counter command found a value that is not a canonical decimal integer. Stack trace disabled (expected case). */
public class NotAnIntegerException extends RuntimeException {

    public NotAnIntegerException() {
        super("ERR value is not an integer or out of range", null, false, false);
    }
}