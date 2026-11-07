package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyValueCommandsTest {

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        router = new CommandRouter();
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
    }

    private RespValue run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        return router.route(command);
    }

    private static RespValue ok() {
        return new RespValue.SimpleString("OK");
    }

    private static RespValue integer(long n) {
        return new RespValue.RespInteger(n);
    }

    private static String bulkText(RespValue reply) {
        assertInstanceOf(RespValue.BulkString.class, reply);
        return new String(((RespValue.BulkString) reply).data(), StandardCharsets.UTF_8);
    }

    @Test
    void setReturnsOkAndGetReturnsTheValue() {
        assertEquals(ok(), run("SET", "name", "arun"));
        assertEquals("arun", bulkText(run("GET", "name")));
    }

    @Test
    void getOfMissingKeyReturnsNullBulkString() {
        RespValue reply = run("GET", "missing");
        assertInstanceOf(RespValue.BulkString.class, reply);
        assertTrue(((RespValue.BulkString) reply).isNull());
    }

    @Test
    void setOverwritesAnExistingKey() {
        run("SET", "k", "one");
        run("SET", "k", "two");
        assertEquals("two", bulkText(run("GET", "k")));
    }

    @Test
    void delReturnsHowManyKeysExisted() {
        run("SET", "a", "1");
        run("SET", "b", "2");
        assertEquals(integer(2), run("DEL", "a", "b", "c"));
    }

    @Test
    void delOfMissingKeyReturnsZero() {
        assertEquals(integer(0), run("DEL", "nothing"));
    }

    @Test
    void getAfterDelReturnsNull() {
        run("SET", "k", "v");
        run("DEL", "k");
        assertTrue(((RespValue.BulkString) run("GET", "k")).isNull());
    }

    @Test
    void existsCountsExistingKeys() {
        run("SET", "a", "1");
        assertEquals(integer(1), run("EXISTS", "a", "b"));
    }

    @Test
    void existsCountsDuplicateKeysTwice() {
        run("SET", "a", "1");
        assertEquals(integer(2), run("EXISTS", "a", "a"));
    }

    @Test
    void keysAreCaseSensitive() {
        run("SET", "Name", "upper");
        assertTrue(((RespValue.BulkString) run("GET", "name")).isNull());
    }

    @Test
    void valuesKeepTheirExactBytes() {
        run("SET", "k", "a b\r\nc");
        assertEquals("a b\r\nc", bulkText(run("GET", "k")));
    }

    @Test
    void wrongArgumentCountsReturnErrors() {
        assertInstanceOf(RespValue.RespError.class, run("SET", "onlykey"));
        assertInstanceOf(RespValue.RespError.class, run("GET"));
        assertInstanceOf(RespValue.RespError.class, run("GET", "a", "b"));
        assertInstanceOf(RespValue.RespError.class, run("DEL"));
        assertInstanceOf(RespValue.RespError.class, run("EXISTS"));
    }

    @Test
    void setWithAnUnsupportedOptionIsASyntaxError() {
        RespValue reply = run("SET", "k", "v", "NX");
        assertEquals(new RespValue.RespError("ERR syntax error"), reply);
    }

    @Test
    void unknownCommandReturnsAnError() { // proves FR-5
        assertInstanceOf(RespValue.RespError.class, run("FLY", "away"));
    }
}