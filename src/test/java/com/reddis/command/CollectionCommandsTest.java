package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollectionCommandsTest {

    private static final String WRONGTYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        router = new CommandRouter();
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        router.register("TYPE", new TypeCommand(store));
        router.register("EXPIRE", new ExpireCommand("expire", store, 1_000));
        router.register("TTL", new TtlCommand("ttl", store, false));
        ListCommands.register(router, store);
        HashCommands.register(router, store);
        SetTypeCommands.register(router, store);
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

    /** The text of a bulk string reply, or null for the null bulk string. */
    private static String text(RespValue reply) {
        RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, reply);
        return bulk.isNull() ? null : new String(bulk.data(), StandardCharsets.UTF_8);
    }

    /** The texts of an array reply. */
    private static List<String> texts(RespValue reply) {
        RespValue.RespArray array = assertInstanceOf(RespValue.RespArray.class, reply);
        return array.elements().stream().map(CollectionCommandsTest::text).toList();
    }

    // ------------------------------------------------------------------ lists

    @Test
    void lpushPushesEachValueToTheFrontInOrder() {
        assertEquals(integer(3), run("LPUSH", "l", "a", "b", "c"));
        assertEquals(List.of("c", "b", "a"), texts(run("LRANGE", "l", "0", "-1")));
    }

    @Test
    void rpushAppendsInOrderAndReturnsTheNewLength() {
        assertEquals(integer(3), run("RPUSH", "l", "a", "b", "c"));
        assertEquals(integer(4), run("RPUSH", "l", "d"));
        assertEquals(List.of("a", "b", "c", "d"), texts(run("LRANGE", "l", "0", "-1")));
    }

    @Test
    void popsTakeFromTheRequestedEnd() {
        run("RPUSH", "l", "a", "b", "c");
        assertEquals("a", text(run("LPOP", "l")));
        assertEquals("c", text(run("RPOP", "l")));
        assertEquals(integer(1), run("LLEN", "l"));
    }

    @Test
    void popOnAMissingKeyReturnsNil() {
        assertNull(text(run("LPOP", "nope")));
        assertNull(text(run("RPOP", "nope")));
    }

    @Test
    void lrangeSupportsNegativeAndOutOfRangeIndices() {
        run("RPUSH", "l", "a", "b", "c", "d");
        assertEquals(List.of("b", "c"), texts(run("LRANGE", "l", "1", "2")));
        assertEquals(List.of("c", "d"), texts(run("LRANGE", "l", "-2", "-1")));
        assertEquals(List.of("a", "b", "c", "d"), texts(run("LRANGE", "l", "-100", "100")));
        assertTrue(texts(run("LRANGE", "l", "5", "10")).isEmpty());
    }

    @Test
    void lindexSupportsNegativeIndices() {
        run("RPUSH", "l", "a", "b", "c");
        assertEquals("a", text(run("LINDEX", "l", "0")));
        assertEquals("c", text(run("LINDEX", "l", "-1")));
        assertNull(text(run("LINDEX", "l", "3")));
    }

    @Test
    void poppingTheLastElementDeletesTheKey() {
        run("RPUSH", "l", "a");
        run("LPOP", "l");
        assertEquals(integer(0), run("EXISTS", "l"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "l"));
    }

    @Test
    void aMissingListBehavesAsEmpty() {
        assertEquals(integer(0), run("LLEN", "nope"));
        assertTrue(texts(run("LRANGE", "nope", "0", "-1")).isEmpty());
        assertEquals(integer(0), run("EXISTS", "nope")); // reading did not create the key
    }

    // ----------------------------------------------------------------- hashes

    @Test
    void hsetCountsOnlyNewFields() {
        assertEquals(integer(2), run("HSET", "h", "f1", "v1", "f2", "v2"));
        assertEquals(integer(1), run("HSET", "h", "f1", "changed", "f3", "v3")); // f1 overwritten: not counted
        assertEquals("changed", text(run("HGET", "h", "f1")));
    }

    @Test
    void hgetReturnsTheFieldOrNil() {
        run("HSET", "h", "name", "arun");
        assertEquals("arun", text(run("HGET", "h", "name")));
        assertNull(text(run("HGET", "h", "missing")));
        assertNull(text(run("HGET", "nokey", "name")));
    }

    @Test
    void hdelRemovesFieldsAndDeletesAnEmptyHash() {
        run("HSET", "h", "a", "1", "b", "2");
        assertEquals(integer(1), run("HDEL", "h", "a", "zzz")); // only 'a' existed
        assertEquals(integer(1), run("HDEL", "h", "b"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "h"));
    }

    @Test
    void hgetallReturnsFlatFieldValuePairs() {
        run("HSET", "h", "f1", "v1", "f2", "v2");
        assertEquals(List.of("f1", "v1", "f2", "v2"), texts(run("HGETALL", "h")));
        assertTrue(texts(run("HGETALL", "nope")).isEmpty());
    }

    @Test
    void hlenAndHexists() {
        run("HSET", "h", "a", "1", "b", "2");
        assertEquals(integer(2), run("HLEN", "h"));
        assertEquals(integer(1), run("HEXISTS", "h", "a"));
        assertEquals(integer(0), run("HEXISTS", "h", "zzz"));
        assertEquals(integer(0), run("HLEN", "nope"));
    }

    // ------------------------------------------------------------------- sets

    @Test
    void saddCountsOnlyNewMembers() {
        assertEquals(integer(2), run("SADD", "s", "a", "b", "a")); // the second 'a' is a duplicate
        assertEquals(integer(1), run("SADD", "s", "b", "c"));
        assertEquals(integer(3), run("SCARD", "s"));
    }

    @Test
    void sismemberReportsMembership() {
        run("SADD", "s", "a");
        assertEquals(integer(1), run("SISMEMBER", "s", "a"));
        assertEquals(integer(0), run("SISMEMBER", "s", "b"));
        assertEquals(integer(0), run("SISMEMBER", "nope", "a"));
    }

    @Test
    void sremRemovesMembersAndDeletesAnEmptySet() {
        run("SADD", "s", "a", "b");
        assertEquals(integer(1), run("SREM", "s", "a", "zzz"));
        assertEquals(integer(1), run("SREM", "s", "b"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "s"));
    }

    @Test
    void smembersReturnsAllMembers() {
        run("SADD", "s", "a", "b", "c");
        // Real Redis does not promise an order for sets, so compare as a set.
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(texts(run("SMEMBERS", "s"))));
        assertTrue(texts(run("SMEMBERS", "nope")).isEmpty());
    }

    // ---------------------------------------------------- TYPE and WRONGTYPE

    @Test
    void typeReportsTheKindOfValue() {
        run("SET", "str", "v");
        run("RPUSH", "lst", "a");
        run("HSET", "hsh", "f", "v");
        run("SADD", "st", "m");

        assertEquals(new RespValue.SimpleString("string"), run("TYPE", "str"));
        assertEquals(new RespValue.SimpleString("list"), run("TYPE", "lst"));
        assertEquals(new RespValue.SimpleString("hash"), run("TYPE", "hsh"));
        assertEquals(new RespValue.SimpleString("set"), run("TYPE", "st"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "nothing"));
    }

    @Test // AC-3
    void commandsOnTheWrongTypeAreRejected() {
        run("SET", "str", "v");
        run("RPUSH", "lst", "a");
        run("HSET", "hsh", "f", "v");

        RespValue wrongType = error(WRONGTYPE);
        assertEquals(wrongType, run("LPUSH", "str", "x"));
        assertEquals(wrongType, run("HSET", "str", "f", "v"));
        assertEquals(wrongType, run("SADD", "str", "m"));
        assertEquals(wrongType, run("GET", "lst"));
        assertEquals(wrongType, run("HGET", "lst", "f"));
        assertEquals(wrongType, run("SMEMBERS", "hsh"));
        assertEquals(wrongType, run("LLEN", "hsh"));

        assertEquals("v", text(run("GET", "str"))); // the failed commands changed nothing
    }

    @Test // AC-4
    void setOverwritesAndDelRemovesAnyType() {
        run("RPUSH", "k", "a");
        assertEquals(new RespValue.SimpleString("OK"), run("SET", "k", "v"));
        assertEquals("v", text(run("GET", "k")));
        assertEquals(new RespValue.SimpleString("string"), run("TYPE", "k"));

        run("HSET", "h", "f", "v");
        assertEquals(integer(1), run("DEL", "h"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "h"));
    }

    @Test
    void expireAndTtlWorkOnCollections() {
        run("RPUSH", "l", "a");
        assertEquals(integer(1), run("EXPIRE", "l", "60"));
        assertEquals(integer(60), run("TTL", "l"));
        run("RPUSH", "l", "b"); // modifying the list must not reset the timeout
        assertEquals(integer(60), run("TTL", "l"));
    }

    // ------------------------------------------------------------ bad input

    @Test
    void wrongArgumentCountsAreRejected() {
        assertInstanceOf(RespValue.RespError.class, run("LPUSH", "l"));
        assertInstanceOf(RespValue.RespError.class, run("LLEN"));
        assertInstanceOf(RespValue.RespError.class, run("LPOP", "a", "b"));
        assertInstanceOf(RespValue.RespError.class, run("LRANGE", "l", "0"));
        assertInstanceOf(RespValue.RespError.class, run("HSET", "h", "f"));          // missing value
        assertInstanceOf(RespValue.RespError.class, run("HSET", "h", "f", "v", "g")); // dangling field
        assertInstanceOf(RespValue.RespError.class, run("HGET", "h"));
        assertInstanceOf(RespValue.RespError.class, run("SADD", "s"));
        assertInstanceOf(RespValue.RespError.class, run("SISMEMBER", "s"));
        assertInstanceOf(RespValue.RespError.class, run("TYPE"));
    }

    @Test
    void nonIntegerIndicesAreRejected() {
        assertEquals(error("ERR value is not an integer or out of range"), run("LRANGE", "l", "a", "b"));
        assertEquals(error("ERR value is not an integer or out of range"), run("LINDEX", "l", "x"));
    }
}