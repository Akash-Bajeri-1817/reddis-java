package com.reddis.command;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteCommandsTest {

    private static List<byte[]> args(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private static List<String> keys(String command, String... args) {
        return WriteCommands.modifiedKeys(command, args(args)).stream()
                .map(key -> new String(key, StandardCharsets.UTF_8)).toList();
    }

    @Test
    void singleKeyWritesReportTheirFirstArgument() {
        assertEquals(List.of("k"), keys("SET", "k", "v"));
        assertEquals(List.of("l"), keys("LPUSH", "l", "a", "b"));
        assertEquals(List.of("z"), keys("ZADD", "z", "1", "m"));
        assertEquals(List.of("k"), keys("EXPIRE", "k", "10"));
    }

    @Test
    void delReportsEveryKeyAndMsetEveryOtherArgument() {
        assertEquals(List.of("a", "b", "c"), keys("DEL", "a", "b", "c"));
        assertEquals(List.of("a", "b"), keys("MSET", "a", "1", "b", "2"));
    }

    @Test
    void readsAndOtherCommandsReportNothing() {
        assertTrue(keys("GET", "k").isEmpty());
        assertTrue(keys("LRANGE", "l", "0", "-1").isEmpty());
        assertTrue(keys("PUBLISH", "news", "hi").isEmpty());
        assertTrue(WriteCommands.modifiedKeys("PING", List.of()).isEmpty());
    }
}