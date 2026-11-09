package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiKeyCommandsTest {

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        router = new CommandRouter();
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("TTL", new TtlCommand("ttl", store, false));
        MultiKeyCommands.register(router, store);
        ListCommands.register(router, store);
    }

    private RespValue run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        return router.route(command);
    }

    private static String text(RespValue reply) {
        RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, reply);
        return bulk.isNull() ? null : new String(bulk.data(), StandardCharsets.UTF_8);
    }

    private static List<String> texts(RespValue reply) {
        RespValue.RespArray array = assertInstanceOf(RespValue.RespArray.class, reply);
        return array.elements().stream().map(MultiKeyCommandsTest::text).toList();
    }

    @Test
    void msetStoresEveryPair() {
        assertEquals(new RespValue.SimpleString("OK"), run("MSET", "a", "1", "b", "2"));
        assertEquals("1", text(run("GET", "a")));
        assertEquals("2", text(run("GET", "b")));
    }

    @Test
    void msetOverwritesAndClearsTimeouts() {
        run("SET", "a", "old", "EX", "100");
        run("MSET", "a", "new");
        assertEquals("new", text(run("GET", "a")));
        assertEquals(new RespValue.RespInteger(-1), run("TTL", "a"));
    }

    @Test
    void msetRequiresCompletePairs() {
        assertInstanceOf(RespValue.RespError.class, run("MSET"));
        assertInstanceOf(RespValue.RespError.class, run("MSET", "a"));
        assertInstanceOf(RespValue.RespError.class, run("MSET", "a", "1", "b"));
        assertNull(text(run("GET", "a"))); // nothing was stored
    }

    @Test
    void mgetReturnsValuesInOrderWithNilForMissingKeys() {
        run("MSET", "a", "1", "c", "3");
        assertEquals(Arrays.asList("1", null, "3"), texts(run("MGET", "a", "b", "c")));
    }

    @Test
    void mgetReturnsNilForKeysOfOtherTypesInsteadOfAnError() {
        run("RPUSH", "list", "x");
        run("SET", "s", "v");
        assertEquals(Arrays.asList(null, "v"), texts(run("MGET", "list", "s")));
    }

    @Test
    void mgetRequiresAtLeastOneKey() {
        assertInstanceOf(RespValue.RespError.class, run("MGET"));
    }
}