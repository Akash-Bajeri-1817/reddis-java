package com.reddis.command;

import com.reddis.support.CommandHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterCommandsTest {

    private static final String NOT_INT = "-ERR value is not an integer or out of range";
    private static final String OVERFLOW = "-ERR increment or decrement would overflow";

    private final CommandHarness h = new CommandHarness();

    @Test
    void incrCreatesTheKeyAtOneAndCounts() {
        assertEquals("1", h.run("INCR", "c"));
        assertEquals("2", h.run("INCR", "c"));
        assertEquals("2", h.run("GET", "c"));
    }

    @Test
    void decrAndTheByVariants() {
        assertEquals("-1", h.run("DECR", "d"));
        assertEquals("10", h.run("INCRBY", "c", "10"));
        assertEquals("7", h.run("DECRBY", "c", "3"));
        assertEquals("5", h.run("INCRBY", "c", "-2"));
    }

    @Test // AC-1
    void aValueThatIsNotACanonicalIntegerIsRejectedAndLeftAlone() {
        for (String bad : new String[]{"abc", "007", "+5", " 5", "1.5", "", "-", "-0", "99999999999999999999"}) {
            h.run("SET", "s", bad);
            assertEquals(NOT_INT, h.run("INCR", "s"), "value: '" + bad + "'");
            assertEquals(bad.isEmpty() ? "" : bad, h.run("GET", "s"));
        }
    }

    @Test
    void anIncrementThatIsNotAnIntegerIsRejected() {
        assertEquals(NOT_INT, h.run("INCRBY", "c", "abc"));
        assertEquals(NOT_INT, h.run("INCRBY", "c", "1.5"));
        assertEquals(NOT_INT, h.run("DECRBY", "c", "x"));
    }

    @Test // AC-1
    void overflowIsDetectedAndNothingChanges() {
        h.run("SET", "big", "9223372036854775807");
        assertEquals(OVERFLOW, h.run("INCR", "big"));
        assertEquals("9223372036854775807", h.run("GET", "big"));

        h.run("SET", "small", "-9223372036854775808");
        assertEquals(OVERFLOW, h.run("DECR", "small"));
        assertEquals(OVERFLOW, h.run("DECRBY", "k", "-9223372036854775808")); // negating Long.MIN_VALUE overflows
    }

    @Test
    void otherTypesAreWrongType() {
        h.run("RPUSH", "l", "x");
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value", h.run("INCR", "l"));
    }

    @Test // AC-1
    void theTimeoutIsKept() {
        h.run("SET", "k", "5", "EX", "100");
        assertEquals("6", h.run("INCR", "k"));
        assertEquals("100", h.run("TTL", "k"));
    }

    @Test
    void anExpiredKeyCountsAsMissing() {
        h.run("SET", "k", "5", "PX", "100");
        h.clock.advance(100);
        assertEquals("1", h.run("INCR", "k"));
        assertEquals("-1", h.run("TTL", "k")); // the new key has no timeout
    }

    @Test
    void wrongArgumentCountsAreRejected() {
        assertEquals("-ERR wrong number of arguments for 'incr' command", h.run("INCR"));
        assertEquals("-ERR wrong number of arguments for 'incr' command", h.run("INCR", "a", "b"));
        assertEquals("-ERR wrong number of arguments for 'incrby' command", h.run("INCRBY", "a"));
        assertEquals("-ERR wrong number of arguments for 'decrby' command", h.run("DECRBY", "a"));
    }
}