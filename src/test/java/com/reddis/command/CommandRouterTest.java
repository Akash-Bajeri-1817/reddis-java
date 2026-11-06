package com.reddis.command;

import com.reddis.protocol.RespValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRouterTest {

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());
    }

    private static List<byte[]> cmd(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    @Test
    void pingReturnsPong() {
        assertEquals(new RespValue.SimpleString("PONG"), router.route(cmd("PING")));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        assertEquals(new RespValue.SimpleString("PONG"), router.route(cmd("pInG")));
    }

    @Test
    void pingWithMessageReturnsMessage() {
        RespValue reply = router.route(cmd("PING", "hi"));
        assertInstanceOf(RespValue.BulkString.class, reply);
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), ((RespValue.BulkString) reply).data());
    }

    @Test
    void echoReturnsItsArgument() {
        RespValue reply = router.route(cmd("ECHO", "hello"));
        assertInstanceOf(RespValue.BulkString.class, reply);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), ((RespValue.BulkString) reply).data());
    }

    @Test
    void echoWithoutArgumentIsAnError() {
        assertInstanceOf(RespValue.RespError.class, router.route(cmd("ECHO")));
    }

    @Test
    void unknownCommandIsAnError() {
        assertInstanceOf(RespValue.RespError.class, router.route(cmd("FOO")));
    }
}