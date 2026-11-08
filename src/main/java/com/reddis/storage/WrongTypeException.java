package com.reddis.storage;

/**
 * A command was used on a key holding another type of value. Translated to a RESP error by the router.
 * Stack traces are disabled: this is normal control flow and filling a trace is expensive.
 */
public class WrongTypeException extends RuntimeException {

    public WrongTypeException() {
        super("WRONGTYPE Operation against a key holding the wrong kind of value",
                null,   // no cause
                false,  // no suppressed exceptions
                false); // no stack trace
    }
}