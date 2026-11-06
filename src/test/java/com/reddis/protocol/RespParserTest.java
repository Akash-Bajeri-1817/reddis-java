package com.reddis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespParserTest {

    private static RespParser parserFor(String input) {
        return new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void parsesPing() throws IOException {
        List<byte[]> cmd = parserFor("*1\r\n$4\r\nPING\r\n").readCommand();
        assertEquals(1, cmd.size());
        assertEquals("PING", text(cmd.get(0)));
    }

    @Test
    void parsesCommandWithArguments() throws IOException {
        List<byte[]> cmd = parserFor("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\narun\r\n").readCommand();
        assertEquals(3, cmd.size());
        assertEquals("SET", text(cmd.get(0)));
        assertEquals("name", text(cmd.get(1)));
        assertEquals("arun", text(cmd.get(2)));
    }

    @Test
    void returnsNullOnCleanEndOfStream() throws IOException {
        assertNull(parserFor("").readCommand());
    }

    @Test
    void parsesTwoCommandsFromOneStream() throws IOException {
        RespParser parser = parserFor("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n");
        assertEquals("PING", text(parser.readCommand().get(0)));
        assertEquals("hi", text(parser.readCommand().get(1)));
        assertNull(parser.readCommand());
    }

    @Test
    void bulkStringMayContainCrLf() throws IOException {
        // The value is "a\r\nb" (4 bytes). A delimiter-based parser would break here.
        List<byte[]> cmd = parserFor("*2\r\n$4\r\nECHO\r\n$4\r\na\r\nb\r\n").readCommand();
        assertEquals("a\r\nb", text(cmd.get(1)));
    }

    @Test
    void handlesInputArrivingOneByteAtATime() throws IOException {
        byte[] bytes = "*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream slow = new InputStream() {
            private int pos = 0;

            @Override
            public int read() {
                return pos < bytes.length ? (bytes[pos++] & 0xFF) : -1;
            }

            @Override
            public int read(byte[] buf, int off, int len) {
                if (len == 0) return 0;
                int b = read();
                if (b == -1) return -1;
                buf[off] = (byte) b;
                return 1; // simulate TCP delivering a single byte per read
            }
        };

        List<byte[]> cmd = new RespParser(slow).readCommand();
        assertEquals("hello", text(cmd.get(1)));
    }

    @Test
    void rejectsWrongFirstByte() {
        assertThrows(ProtocolException.class, () -> parserFor("+OK\r\n").readCommand());
    }

    @Test
    void rejectsNonNumericLength() {
        assertThrows(ProtocolException.class, () -> parserFor("*1\r\n$abc\r\n").readCommand());
    }

    @Test
    void rejectsMissingCrLfAfterBulkString() {
        assertThrows(ProtocolException.class, () -> parserFor("*1\r\n$4\r\nPINGxx").readCommand());
    }

    @Test
    void rejectsHugeBulkLength() {
        assertThrows(ProtocolException.class, () -> parserFor("*1\r\n$99999999999\r\n").readCommand());
    }

    @Test
    void truncatedInputThrowsEof() {
        assertThrows(EOFException.class, () -> parserFor("*1\r\n$4\r\nPI").readCommand());
    }
}