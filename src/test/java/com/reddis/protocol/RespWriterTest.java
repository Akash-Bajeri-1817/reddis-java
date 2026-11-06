package com.reddis.protocol;

import com.reddis.protocol.RespValue.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RespWriterTest {

    private static String encode(RespValue value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespWriter writer = new RespWriter(out);
        writer.write(value);
        writer.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void encodesSimpleString() throws IOException {
        assertEquals("+OK\r\n", encode(new SimpleString("OK")));
    }

    @Test
    void encodesError() throws IOException {
        assertEquals("-ERR boom\r\n", encode(new RespError("ERR boom")));
    }

    @Test
    void encodesInteger() throws IOException {
        assertEquals(":42\r\n", encode(new RespInteger(42)));
    }

    @Test
    void encodesBulkString() throws IOException {
        assertEquals("$5\r\nhello\r\n", encode(BulkString.of("hello")));
    }

    @Test
    void encodesNullBulkString() throws IOException {
        assertEquals("$-1\r\n", encode(BulkString.NULL));
    }

    @Test
    void encodesArray() throws IOException {
        RespValue array = new RespArray(List.of(BulkString.of("a"), new RespInteger(1)));
        assertEquals("*2\r\n$1\r\na\r\n:1\r\n", encode(array));
    }

    @Test
    void stripsCrLfFromLineStyleTypes() throws IOException {
        assertEquals("+a  b\r\n", encode(new SimpleString("a\r\nb")));
    }
}