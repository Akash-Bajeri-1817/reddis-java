package com.reddis.command;

import com.reddis.support.CommandHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetNxXxTest {

    private final CommandHarness h = new CommandHarness();

    @Test
    void nxSetsOnlyWhenTheKeyIsMissing() {
        assertEquals("OK", h.run("SET", "k", "1", "NX"));
        assertEquals("(nil)", h.run("SET", "k", "2", "NX"));
        assertEquals("1", h.run("GET", "k"));
    }

    @Test
    void xxSetsOnlyWhenTheKeyExists() {
        assertEquals("(nil)", h.run("SET", "k", "1", "XX"));
        assertEquals("(nil)", h.run("GET", "k"));
        h.run("SET", "k", "1");
        assertEquals("OK", h.run("SET", "k", "2", "XX"));
        assertEquals("2", h.run("GET", "k"));
    }

    @Test
    void nxAndXxTogetherAreASyntaxError() {
        assertEquals("-ERR syntax error", h.run("SET", "k", "v", "NX", "XX"));
        assertEquals("-ERR syntax error", h.run("SET", "k", "v", "NX", "NX"));
    }

    @Test // the classic lock
    void nxCombinesWithAnExpiry() {
        assertEquals("OK", h.run("SET", "lock", "owner-1", "NX", "EX", "10"));
        assertEquals("10", h.run("TTL", "lock"));
        assertEquals("(nil)", h.run("SET", "lock", "owner-2", "NX", "EX", "10"));
        assertEquals("owner-1", h.run("GET", "lock"));
    }

    @Test // AC-2
    void aFailedNxLeavesTheExistingTimeoutAlone() {
        h.run("SET", "k", "v", "EX", "100");
        assertEquals("(nil)", h.run("SET", "k", "w", "NX", "EX", "5"));
        assertEquals("100", h.run("TTL", "k"));
    }

    @Test
    void optionsAreCaseInsensitive() {
        assertEquals("OK", h.run("SET", "k", "v", "nx", "ex", "10"));
        assertEquals("10", h.run("TTL", "k"));
    }

    @Test
    void nxTreatsAKeyOfAnyTypeAsExisting() {
        h.run("RPUSH", "l", "x");
        assertEquals("(nil)", h.run("SET", "l", "v", "NX"));
        assertEquals("list", h.run("TYPE", "l"));
    }
}