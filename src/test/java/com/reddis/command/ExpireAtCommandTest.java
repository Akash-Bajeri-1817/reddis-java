package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ExpireAtCommandTest {

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        // clock at 1_000_000 ms = unix second 1000
        router = CommandRegistry.standard(new DataStore(new MutableClock(1_000_000L)));
    }

    private RespValue run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        return router.route(command);
    }

    private static RespValue integer(long n) {
        return new RespValue.RespInteger(n);
    }

    @Test
    void expireatSetsAnAbsoluteDeadlineInSeconds() {
        run("SET", "k", "v");
        assertEquals(integer(1), run("EXPIREAT", "k", "1005"));
        assertEquals(integer(5), run("TTL", "k"));
    }

    @Test
    void pexpireatSetsAnAbsoluteDeadlineInMilliseconds() {
        run("SET", "k", "v");
        assertEquals(integer(1), run("PEXPIREAT", "k", "1002500"));
        assertEquals(integer(2_500), run("PTTL", "k"));
    }

    @Test
    void aDeadlineInThePastDeletesTheKey() {
        run("SET", "k", "v");
        assertEquals(integer(1), run("EXPIREAT", "k", "500"));
        assertEquals(integer(0), run("EXISTS", "k"));
    }

    @Test
    void aMissingKeyReturnsZero() {
        assertEquals(integer(0), run("PEXPIREAT", "nope", "2000000"));
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertInstanceOf(RespValue.RespError.class, run("EXPIREAT", "k"));
        assertEquals(new RespValue.RespError("ERR value is not an integer or out of range"),
                run("PEXPIREAT", "k", "abc"));
        assertEquals(new RespValue.RespError("ERR invalid expire time in 'expireat' command"),
                run("EXPIREAT", "k", "9223372036854775807")); // seconds -> milliseconds overflows
    }
}