package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpiryCommandsTest {

    private MutableClock clock;
    private CommandRouter router;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        DataStore store = new DataStore(clock);
        router = new CommandRouter();
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        router.register("EXPIRE", new ExpireCommand("expire", store, 1_000));
        router.register("PEXPIRE", new ExpireCommand("pexpire", store, 1));
        router.register("TTL", new TtlCommand("ttl", store, false));
        router.register("PTTL", new TtlCommand("pttl", store, true));
        router.register("PERSIST", new PersistCommand(store));
    }

    private RespValue run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        return router.route(command);
    }

    private static RespValue integer(long n) {
        return new RespValue.RespInteger(n);
    }

    private static RespValue error(String message) {
        return new RespValue.RespError(message);
    }

    private static RespValue ok() {
        return new RespValue.SimpleString("OK");
    }

    private boolean isNull(RespValue reply) {
        return reply instanceof RespValue.BulkString bulk && bulk.isNull();
    }

    @Test
    void setWithExSetsATimeoutInSeconds() {
        assertEquals(ok(), run("SET", "k", "v", "EX", "10"));
        assertEquals(integer(10), run("TTL", "k"));

        clock.advance(4_000);
        assertEquals(integer(6), run("TTL", "k"));

        clock.advance(6_000);
        assertTrue(isNull(run("GET", "k")));
        assertEquals(integer(-2), run("TTL", "k"));
    }

    @Test
    void setWithPxAndTtlRoundsToTheNearestSecond() {
        run("SET", "k", "v", "PX", "1500");
        assertEquals(integer(1_500), run("PTTL", "k"));
        assertEquals(integer(2), run("TTL", "k")); // 1.5 s rounds up, like Redis

        clock.advance(1_001); // 499 ms left
        assertEquals(integer(0), run("TTL", "k"));
    }

    @Test
    void optionsAreCaseInsensitive() {
        assertEquals(ok(), run("SET", "k", "v", "ex", "10"));
        assertEquals(integer(10), run("TTL", "k"));
    }

    @Test
    void plainSetClearsTheTimeout() {
        run("SET", "k", "v", "EX", "10");
        run("SET", "k", "v2");
        assertEquals(integer(-1), run("TTL", "k"));
    }

    @Test
    void expireSetsATimeoutOnAnExistingKey() {
        run("SET", "k", "v");
        assertEquals(integer(1), run("EXPIRE", "k", "5"));
        assertEquals(integer(5), run("TTL", "k"));
    }

    @Test
    void expireOnAMissingKeyReturnsZero() {
        assertEquals(integer(0), run("EXPIRE", "nope", "5"));
    }

    @Test
    void expireWithANegativeValueDeletesTheKey() {
        run("SET", "k", "v");
        assertEquals(integer(1), run("EXPIRE", "k", "-1"));
        assertTrue(isNull(run("GET", "k")));
    }

    @Test
    void pexpireAndPttlWorkInMilliseconds() {
        run("SET", "k", "v");
        assertEquals(integer(1), run("PEXPIRE", "k", "2500"));
        assertEquals(integer(2_500), run("PTTL", "k"));
    }

    @Test
    void ttlSpecialValues() {
        run("SET", "k", "v");
        assertEquals(integer(-1), run("TTL", "k"));      // exists, no timeout
        assertEquals(integer(-2), run("TTL", "nope"));   // does not exist
    }

    @Test
    void persistRemovesTheTimeout() {
        run("SET", "k", "v", "EX", "10");
        assertEquals(integer(1), run("PERSIST", "k"));
        assertEquals(integer(-1), run("TTL", "k"));
        assertEquals(integer(0), run("PERSIST", "k"));
        clock.advance(60_000);
        assertEquals(integer(1), run("EXISTS", "k"));
    }

    @Test
    void invalidSetOptionsAreRejectedAndNothingIsStored() {
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "EX", "10", "PX", "5000"));
        assertEquals(error("ERR syntax error"), run("SET", "k", "v", "NX"));
        assertEquals(error("ERR value is not an integer or out of range"), run("SET", "k", "v", "EX", "abc"));
        assertEquals(error("ERR invalid expire time in 'set' command"), run("SET", "k", "v", "EX", "0"));
        assertEquals(error("ERR invalid expire time in 'set' command"), run("SET", "k", "v", "EX", "-5"));
        assertEquals(error("ERR invalid expire time in 'set' command"),
                run("SET", "k", "v", "EX", "9223372036854775807")); // seconds -> millis would overflow

        assertTrue(isNull(run("GET", "k"))); // a rejected SET must not have stored anything
    }

    @Test
    void invalidExpireArgumentsAreRejected() {
        assertEquals(error("ERR wrong number of arguments for 'expire' command"), run("EXPIRE", "k"));
        assertEquals(error("ERR value is not an integer or out of range"), run("EXPIRE", "k", "abc"));
        assertEquals(error("ERR invalid expire time in 'expire' command"),
                run("EXPIRE", "k", "9223372036854775807"));
        assertEquals(error("ERR wrong number of arguments for 'ttl' command"), run("TTL"));
        assertEquals(error("ERR wrong number of arguments for 'persist' command"), run("PERSIST"));
    }

    @Test
    void keyWithATimeoutExistsUntilItsDeadline() {
        run("SET", "k", "v", "PX", "1000");
        clock.advance(999);
        assertEquals(integer(1), run("EXISTS", "k"));
        clock.advance(1);
        assertEquals(integer(0), run("EXISTS", "k"));
    }
}