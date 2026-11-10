package com.reddis.persistence;

import java.util.Locale;

/** How often the append-only file is forced onto stable storage. */
public enum FsyncPolicy {
    /** fsync before replying: no acknowledged write is ever lost. Slowest. */
    ALWAYS,
    /** fsync in the background once per second: lose about a second at worst. */
    EVERYSEC,
    /** never fsync: the OS flushes when it likes. Fastest, least safe. */
    NO;

    public static FsyncPolicy parse(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "always" -> ALWAYS;
            case "everysec" -> EVERYSEC;
            case "no" -> NO;
            default -> throw new IllegalArgumentException("appendfsync must be always, everysec or no, not: " + text);
        };
    }
}