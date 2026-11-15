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

/** AC-5: what the AOF and replicas see for the new commands and for SET NX/XX. */
class CommandPropagatorSetOptionsTest {

    private DataStore store;
    private CommandPropagator propagator;

    @BeforeEach
    void setUp() {
        store = new DataStore(new MutableClock(1_000_000L));
        propagator = new CommandPropagator(store);
    }

    private static List<byte[]> cmd(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private List<List<String>> propagate(List<byte[]> command, RespValue reply) {
        return propagator.propagate(command, reply).stream()
                .map(record -> record.stream().map(part -> new String(part, StandardCharsets.UTF_8)).toList()).toList();
    }

    private static RespValue ok() {
        return new RespValue.SimpleString("OK");
    }

    @Test
    void aSetThatDidNotHappenIsNotLogged() {
        assertTrue(propagate(cmd("SET", "k", "v", "NX"), RespValue.BulkString.NULL).isEmpty());
        assertTrue(propagate(cmd("SET", "k", "v", "XX", "EX", "10"), RespValue.BulkString.NULL).isEmpty());
    }

    @Test
    void nxAndXxAreDroppedFromTheLoggedCommand() {
        store.set("k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(List.of("SET", "k", "v")), propagate(cmd("SET", "k", "v", "NX"), ok()));
        assertEquals(List.of(List.of("SET", "k", "v")), propagate(cmd("SET", "k", "v", "XX"), ok()));
    }

    @Test
    void nxWithAnExpiryBecomesAPlainSetPlusAnAbsoluteDeadline() {
        store.set("k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8), 10_000);
        assertEquals(List.of(List.of("SET", "k", "v"), List.of("PEXPIREAT", "k", "1010000")),
                propagate(cmd("SET", "k", "v", "NX", "EX", "10"), ok()));
    }

    @Test
    void theNewWriteCommandsAreLoggedVerbatim() {
        assertEquals(List.of(List.of("INCR", "c")), propagate(cmd("INCR", "c"), new RespValue.RespInteger(1)));
        assertEquals(List.of(List.of("DECRBY", "c", "5")), propagate(cmd("DECRBY", "c", "5"), new RespValue.RespInteger(-5)));
        assertEquals(List.of(List.of("APPEND", "s", "x")), propagate(cmd("APPEND", "s", "x"), new RespValue.RespInteger(1)));
        assertEquals(List.of(List.of("RENAME", "a", "b")), propagate(cmd("RENAME", "a", "b"), ok()));
        assertEquals(List.of(List.of("FLUSHALL")), propagate(cmd("FLUSHALL"), ok()));
        assertTrue(propagate(cmd("STRLEN", "s"), new RespValue.RespInteger(1)).isEmpty()); // a read
        assertTrue(propagate(cmd("KEYS", "*"), new RespValue.RespArray(List.of())).isEmpty());
    }
}