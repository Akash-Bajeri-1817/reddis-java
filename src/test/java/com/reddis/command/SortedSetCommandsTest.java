package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortedSetCommandsTest {

    private static final String NOT_A_FLOAT = "ERR value is not a valid float";

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        router = new CommandRouter();
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        router.register("TYPE", new TypeCommand(store));
        SortedSetCommands.register(router, store);
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

    private static String text(RespValue reply) {
        RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, reply);
        return bulk.isNull() ? null : new String(bulk.data(), StandardCharsets.UTF_8);
    }

    private static List<String> texts(RespValue reply) {
        RespValue.RespArray array = assertInstanceOf(RespValue.RespArray.class, reply);
        return array.elements().stream().map(SortedSetCommandsTest::text).toList();
    }

    @Test
    void zaddCountsOnlyNewMembers() {
        assertEquals(integer(2), run("ZADD", "z", "1", "a", "2", "b"));
        assertEquals(integer(1), run("ZADD", "z", "5", "a", "3", "c")); // 'a' is updated, 'c' is new
        assertEquals(integer(3), run("ZCARD", "z"));
    }

    @Test
    void zscorePrintsScoresLikeRedis() {
        run("ZADD", "z", "1", "a", "2.5", "b", "-3", "c", "1e2", "d", "inf", "e", "-inf", "f");
        assertEquals("1", text(run("ZSCORE", "z", "a")));      // not "1.0"
        assertEquals("2.5", text(run("ZSCORE", "z", "b")));
        assertEquals("-3", text(run("ZSCORE", "z", "c")));
        assertEquals("100", text(run("ZSCORE", "z", "d")));    // not "1.0E2"
        assertEquals("inf", text(run("ZSCORE", "z", "e")));
        assertEquals("-inf", text(run("ZSCORE", "z", "f")));
        assertNull(text(run("ZSCORE", "z", "missing")));
        assertNull(text(run("ZSCORE", "nokey", "a")));
    }

    @Test
    void zrangeReturnsMembersByAscendingScore() {
        run("ZADD", "z", "3", "c", "1", "a", "2", "b");
        assertEquals(List.of("a", "b", "c"), texts(run("ZRANGE", "z", "0", "-1")));
    }

    @Test
    void zrangeWithScoresInterleavesMembersAndScores() {
        run("ZADD", "z", "3", "c", "1", "a", "2.5", "b");
        assertEquals(List.of("a", "1", "b", "2.5", "c", "3"), texts(run("ZRANGE", "z", "0", "-1", "WITHSCORES")));
        assertEquals(List.of("a", "1"), texts(run("ZRANGE", "z", "0", "0", "withscores"))); // case-insensitive
    }

    @Test
    void zrangeSupportsNegativeAndOutOfRangeIndices() {
        run("ZADD", "z", "1", "a", "2", "b", "3", "c", "4", "d");
        assertEquals(List.of("c", "d"), texts(run("ZRANGE", "z", "-2", "-1")));
        assertEquals(List.of("a", "b", "c", "d"), texts(run("ZRANGE", "z", "-100", "100")));
        assertTrue(texts(run("ZRANGE", "z", "9", "12")).isEmpty());
        assertTrue(texts(run("ZRANGE", "nokey", "0", "-1")).isEmpty());
    }

    @Test
    void zrevrangeReturnsTheHighestScoresFirst() {
        run("ZADD", "leaderboard", "100", "alice", "250", "bob", "175", "carol");
        assertEquals(List.of("bob", "carol"), texts(run("ZREVRANGE", "leaderboard", "0", "1")));
        assertEquals(List.of("bob", "250", "carol", "175", "alice", "100"),
                texts(run("ZREVRANGE", "leaderboard", "0", "-1", "WITHSCORES")));
    }

    @Test
    void equalScoresAreOrderedByMemberBytes() {
        run("ZADD", "z", "1", "b", "1", "a", "1", "c");
        assertEquals(List.of("a", "b", "c"), texts(run("ZRANGE", "z", "0", "-1")));
    }

    @Test
    void zrankIsZeroBasedAndNilForMissing() {
        run("ZADD", "z", "1", "a", "2", "b", "3", "c");
        assertEquals(integer(0), run("ZRANK", "z", "a"));
        assertEquals(integer(2), run("ZRANK", "z", "c"));
        assertNull(text(run("ZRANK", "z", "missing")));
        assertNull(text(run("ZRANK", "nokey", "a")));
    }

    @Test
    void zremRemovesMembersAndDeletesAnEmptySet() {
        run("ZADD", "z", "1", "a", "2", "b");
        assertEquals(integer(1), run("ZREM", "z", "a", "zzz"));
        assertEquals(integer(1), run("ZREM", "z", "b"));
        assertEquals(new RespValue.SimpleString("none"), run("TYPE", "z"));
        assertEquals(integer(0), run("EXISTS", "z"));
    }

    @Test
    void zincrbyAddsToTheScoreAndCreatesMembers() {
        assertEquals("5", text(run("ZINCRBY", "z", "5", "a")));     // new member starts from 0
        assertEquals("2.5", text(run("ZINCRBY", "z", "-2.5", "a")));
        assertEquals("2.5", text(run("ZSCORE", "z", "a")));
    }

    @Test
    void zincrbyRejectsAResultThatWouldBeNaN() {
        run("ZADD", "z", "inf", "a");
        assertEquals(error("ERR resulting score is not a number (NaN)"), run("ZINCRBY", "z", "-inf", "a"));
        assertEquals("inf", text(run("ZSCORE", "z", "a"))); // unchanged
    }

    @Test
    void updatingAScoreMovesTheMember() {
        run("ZADD", "z", "1", "a", "2", "b");
        assertEquals(integer(0), run("ZADD", "z", "10", "a")); // update: nothing new
        assertEquals(List.of("b", "a"), texts(run("ZRANGE", "z", "0", "-1")));
        assertEquals(integer(1), run("ZRANK", "z", "a"));
    }

    @Test
    void typeIsZsetAndOtherTypesAreRejected() {
        run("ZADD", "z", "1", "a");
        run("SET", "str", "v");
        String wrongType = "WRONGTYPE Operation against a key holding the wrong kind of value";

        assertEquals(new RespValue.SimpleString("zset"), run("TYPE", "z"));
        assertEquals(error(wrongType), run("GET", "z"));
        assertEquals(error(wrongType), run("ZADD", "str", "1", "a"));
        assertEquals(error(wrongType), run("ZRANGE", "str", "0", "-1"));
    }

    @Test
    void invalidScoresAreRejectedWithoutPartialEffects() {
        assertEquals(error(NOT_A_FLOAT), run("ZADD", "z", "abc", "a"));
        assertEquals(error(NOT_A_FLOAT), run("ZADD", "z", "nan", "a"));   // Double.parseDouble would accept this
        assertEquals(error(NOT_A_FLOAT), run("ZADD", "z", "1.5f", "a"));  // ...and this
        assertEquals(error(NOT_A_FLOAT), run("ZADD", "z", "1e999", "a")); // overflows to infinity
        assertEquals(error(NOT_A_FLOAT), run("ZADD", "z", "1", "a", "bad", "b"));
        assertEquals(integer(0), run("EXISTS", "z")); // not even the valid first pair was stored
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertInstanceOf(RespValue.RespError.class, run("ZADD", "z"));
        assertInstanceOf(RespValue.RespError.class, run("ZADD", "z", "1"));
        assertInstanceOf(RespValue.RespError.class, run("ZADD", "z", "1", "a", "2")); // dangling score
        assertInstanceOf(RespValue.RespError.class, run("ZSCORE", "z"));
        assertInstanceOf(RespValue.RespError.class, run("ZRANK", "z"));
        assertInstanceOf(RespValue.RespError.class, run("ZREM", "z"));
        assertInstanceOf(RespValue.RespError.class, run("ZCARD"));
        assertInstanceOf(RespValue.RespError.class, run("ZRANGE", "z", "0"));
        assertInstanceOf(RespValue.RespError.class, run("ZINCRBY", "z", "1"));

        assertEquals(error("ERR syntax error"), run("ZRANGE", "z", "0", "-1", "FOO"));
        assertEquals(error("ERR value is not an integer or out of range"), run("ZRANGE", "z", "a", "b"));
        assertEquals(error(NOT_A_FLOAT), run("ZINCRBY", "z", "abc", "m"));
    }
}