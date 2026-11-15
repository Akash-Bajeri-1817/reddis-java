package com.reddis.command;

import com.reddis.support.CommandHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringCommandsTest {

    private final CommandHarness h = new CommandHarness();

    @Test
    void appendCreatesTheKeyAndReturnsTheNewLength() {
        assertEquals("2", h.run("APPEND", "k", "ab"));
        assertEquals("5", h.run("APPEND", "k", "cde"));
        assertEquals("abcde", h.run("GET", "k"));
    }

    @Test
    void strlenReportsTheLengthAndZeroForMissingKeys() {
        h.run("SET", "k", "hello");
        assertEquals("5", h.run("STRLEN", "k"));
        assertEquals("0", h.run("STRLEN", "missing"));
    }

    @Test
    void appendIsBinarySafe() {
        assertEquals("3", h.run("APPEND", "b", "a\r\n"));
        assertEquals("3", h.run("STRLEN", "b"));
    }

    @Test
    void appendKeepsTheTimeout() {
        h.run("SET", "k", "v", "EX", "100");
        h.run("APPEND", "k", "x");
        assertEquals("100", h.run("TTL", "k"));
    }

    @Test
    void otherTypesAreWrongType() {
        h.run("RPUSH", "l", "x");
        String wrongType = "-WRONGTYPE Operation against a key holding the wrong kind of value";
        assertEquals(wrongType, h.run("APPEND", "l", "y"));
        assertEquals(wrongType, h.run("STRLEN", "l"));
    }

    @Test
    void wrongArgumentCountsAreRejected() {
        assertEquals("-ERR wrong number of arguments for 'append' command", h.run("APPEND", "k"));
        assertEquals("-ERR wrong number of arguments for 'strlen' command", h.run("STRLEN"));
    }
}