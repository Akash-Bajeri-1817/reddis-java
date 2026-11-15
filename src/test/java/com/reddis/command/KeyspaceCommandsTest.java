package com.reddis.command;

import com.reddis.support.CommandHarness;
import com.reddis.support.StoreDump;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyspaceCommandsTest {

    private final CommandHarness h = new CommandHarness();

    /** KEYS returns an unordered array rendered as "[a,b]": turn it into a sorted list. */
    private static List<String> sorted(String reply) {
        if (reply.equals("[]")) {
            return List.of();
        }
        return Arrays.stream(reply.substring(1, reply.length() - 1).split(",")).sorted().toList();
    }

    @Test
    void keysMatchesGlobPatterns() {
        h.run("SET", "user:1", "a");
        h.run("SET", "user:2", "b");
        h.run("SET", "order:1", "c");

        assertEquals(List.of("order:1", "user:1", "user:2"), sorted(h.run("KEYS", "*")));
        assertEquals(List.of("user:1", "user:2"), sorted(h.run("KEYS", "user:*")));
        assertEquals(List.of("user:1"), sorted(h.run("KEYS", "?ser:1")));
        assertEquals(List.of(), sorted(h.run("KEYS", "nothing*")));
    }

    @Test
    void keysSkipsExpiredKeys() {
        h.run("SET", "a", "1", "PX", "100");
        h.run("SET", "b", "1");
        h.clock.advance(100);
        assertEquals(List.of("b"), sorted(h.run("KEYS", "*")));
    }

    @Test // AC-3
    void renameMovesTheValueAndTheTimeout() {
        h.run("SET", "a", "v", "EX", "100");
        assertEquals("OK", h.run("RENAME", "a", "b"));
        assertEquals("(nil)", h.run("GET", "a"));
        assertEquals("v", h.run("GET", "b"));
        assertEquals("100", h.run("TTL", "b"));
    }

    @Test
    void renameOverwritesTheTarget() {
        h.run("SET", "a", "1");
        h.run("SET", "b", "2");
        h.run("RENAME", "a", "b");
        assertEquals("1", h.run("GET", "b"));
        assertEquals("1", h.run("DBSIZE"));
    }

    @Test
    void renameWorksForCollections() {
        h.run("RPUSH", "l", "x", "y");
        assertEquals("OK", h.run("RENAME", "l", "m"));
        assertEquals("[x,y]", h.run("LRANGE", "m", "0", "-1"));
        assertEquals("0", h.run("EXISTS", "l"));
    }

    @Test
    void renameOfAMissingKeyIsAnErrorAndRenamingOntoItselfIsFine() {
        assertEquals("-ERR no such key", h.run("RENAME", "nope", "x"));
        assertEquals("-ERR no such key", h.run("RENAME", "nope", "nope"));
        h.run("SET", "a", "1");
        assertEquals("OK", h.run("RENAME", "a", "a"));
        assertEquals("1", h.run("GET", "a"));
    }

    @Test
    void flushallAndFlushdbEmptyEverything() {
        StoreDump.populate(h.store);
        assertEquals("OK", h.run("FLUSHALL"));
        assertEquals("0", h.run("DBSIZE"));
        h.store.validate();

        h.run("SET", "a", "1");
        assertEquals("OK", h.run("FLUSHDB"));
        assertEquals("0", h.run("DBSIZE"));
        assertEquals("OK", h.run("FLUSHALL", "ASYNC"));
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertEquals("-ERR syntax error", h.run("FLUSHALL", "bogus"));
        assertEquals("-ERR wrong number of arguments for 'keys' command", h.run("KEYS"));
        assertEquals("-ERR wrong number of arguments for 'rename' command", h.run("RENAME", "a"));
    }
}