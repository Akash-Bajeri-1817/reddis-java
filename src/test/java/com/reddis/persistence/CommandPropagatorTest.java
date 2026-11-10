package com.reddis.persistence;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPropagatorTest {

    private MutableClock clock;
    private DataStore store;
    private CommandPropagator propagator;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        store = new DataStore(clock);
        propagator = new CommandPropagator(store);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<byte[]> cmd(String... parts) {
        return Arrays.stream(parts).map(StandardCharsets.UTF_8::encode)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    return bytes;
                }).toList();
    }

    private List<List<String>> propagate(List<byte[]> command, RespValue reply) {
        return propagator.propagate(command, reply).stream()
                .map(record -> record.stream().map(part -> new String(part, StandardCharsets.UTF_8)).toList())
                .toList();
    }

    private static RespValue ok() {
        return new RespValue.SimpleString("OK");
    }

    @Test
    void readsAreNotLogged() {
        assertTrue(propagate(cmd("GET", "k"), new RespValue.BulkString(b("v"))).isEmpty());
        assertTrue(propagate(cmd("TTL", "k"), new RespValue.RespInteger(-1)).isEmpty());
        assertTrue(propagate(cmd("PING"), new RespValue.SimpleString("PONG")).isEmpty());
        assertTrue(propagate(cmd("SAVE"), ok()).isEmpty());
    }

    @Test // AC-4
    void failedCommandsAreNotLogged() {
        assertTrue(propagate(cmd("SET", "k"), new RespValue.RespError("ERR wrong number")).isEmpty());
        assertTrue(propagate(cmd("LPUSH", "k", "x"), new RespValue.RespError("WRONGTYPE")).isEmpty());
    }

    @Test
    void deterministicWritesAreLoggedAsTheyAre() {
        assertEquals(List.of(List.of("LPUSH", "l", "a", "b")),
                propagate(cmd("LPUSH", "l", "a", "b"), new RespValue.RespInteger(2)));
        assertEquals(List.of(List.of("ZADD", "z", "1.5", "m")),
                propagate(cmd("ZADD", "z", "1.5", "m"), new RespValue.RespInteger(1)));
        assertEquals(List.of(List.of("DEL", "a", "b")),
                propagate(cmd("DEL", "a", "b"), new RespValue.RespInteger(1)));
        assertEquals(List.of(List.of("MSET", "a", "1", "b", "2")),
                propagate(cmd("MSET", "a", "1", "b", "2"), ok()));
    }

    @Test
    void aPlainSetIsLoggedAsItIs() {
        assertEquals(List.of(List.of("SET", "k", "v")), propagate(cmd("SET", "k", "v"), ok()));
    }

    @Test // AC-5
    void setWithExBecomesSetPlusAnAbsoluteDeadline() {
        store.set(b("k"), b("v"), 10_000); // the command has just executed
        assertEquals(
                List.of(List.of("SET", "k", "v"), List.of("PEXPIREAT", "k", "1010000")),
                propagate(cmd("SET", "k", "v", "EX", "10"), ok()));
    }

    @Test // AC-5
    void setWithPxBecomesSetPlusAnAbsoluteDeadline() {
        store.set(b("k"), b("v"), 2_500);
        assertEquals(
                List.of(List.of("SET", "k", "v"), List.of("PEXPIREAT", "k", "1002500")),
                propagate(cmd("SET", "k", "v", "PX", "2500"), ok()));
    }

    @Test // AC-5
    void expireBecomesPexpireat() {
        store.set(b("k"), b("v"));
        store.expire(b("k"), 5_000);
        assertEquals(List.of(List.of("PEXPIREAT", "k", "1005000")),
                propagate(cmd("EXPIRE", "k", "5"), new RespValue.RespInteger(1)));
    }

    @Test // AC-5
    void anExpireThatDeletedTheKeyBecomesDel() {
        store.set(b("k"), b("v"));
        store.expire(b("k"), -1); // deletes the key
        assertEquals(List.of(List.of("DEL", "k")),
                propagate(cmd("EXPIRE", "k", "-1"), new RespValue.RespInteger(1)));
    }

    @Test
    void anExpireOnAMissingKeyIsNotLogged() {
        assertTrue(propagate(cmd("EXPIRE", "nope", "5"), new RespValue.RespInteger(0)).isEmpty());
    }

    @Test
    void absoluteExpiryCommandsAreLoggedAsTheyAre() {
        assertEquals(List.of(List.of("PEXPIREAT", "k", "1234567")),
                propagate(cmd("PEXPIREAT", "k", "1234567"), new RespValue.RespInteger(1)));
    }
}