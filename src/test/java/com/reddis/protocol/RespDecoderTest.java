package com.reddis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RespDecoderTest {

    private final RespDecoder decoder = new RespDecoder();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static ByteBuffer buffer(String s) {
        return ByteBuffer.wrap(bytes(s));
    }

    @Test
    void decodesACompleteCommand() throws Exception {
        ByteBuffer buf = buffer("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\narun\r\n");
        List<byte[]> command = decoder.decode(buf);

        assertEquals(3, command.size());
        assertEquals("SET", text(command.get(0)));
        assertEquals("name", text(command.get(1)));
        assertEquals("arun", text(command.get(2)));
        assertFalse(buf.hasRemaining()); // the whole command was consumed
    }

    @Test // AC-3: the heart of non-blocking parsing
    void everyProperPrefixOfACommandIsIncompleteAndConsumesNothing() throws Exception {
        byte[] command = bytes("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\narun\r\n");

        for (int length = 0; length < command.length; length++) {
            ByteBuffer buf = ByteBuffer.wrap(command, 0, length); // only the first 'length' bytes have arrived
            assertNull(decoder.decode(buf), "prefix of length " + length);
            assertEquals(0, buf.position(), "position moved for prefix of length " + length);
        }

        ByteBuffer whole = ByteBuffer.wrap(command);
        assertNotNull(decoder.decode(whole));
        assertEquals(command.length, whole.position());
    }

    @Test
    void decodesSeveralCommandsFromOneBuffer() throws Exception {
        ByteBuffer buf = buffer("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n");
        assertEquals("PING", text(decoder.decode(buf).get(0)));
        assertEquals("hi", text(decoder.decode(buf).get(1)));
        assertNull(decoder.decode(buf)); // nothing left
    }

    @Test
    void aBulkStringMayContainCrLf() throws Exception {
        List<byte[]> command = decoder.decode(buffer("*2\r\n$4\r\nECHO\r\n$4\r\na\r\nb\r\n"));
        assertEquals("a\r\nb", text(command.get(1)));
    }

    @Test
    void emptyAndNullArraysAreConsumedAsEmptyCommands() throws Exception {
        ByteBuffer buf = buffer("*0\r\n*-1\r\n");
        assertTrue(decoder.decode(buf).isEmpty());
        assertTrue(decoder.decode(buf).isEmpty());
        assertFalse(buf.hasRemaining());
    }

    @Test
    void rejectsAWrongFirstByte() {
        assertThrows(ProtocolException.class, () -> decoder.decode(buffer("+OK\r\n")));
        assertThrows(ProtocolException.class, () -> decoder.decode(buffer("hello\r\n")));
    }

    @Test
    void rejectsANonNumericLength() {
        assertThrows(ProtocolException.class, () -> decoder.decode(buffer("*1\r\n$abc\r\n")));
    }

    @Test
    void rejectsAMissingCrLfAfterABulkString() {
        assertThrows(ProtocolException.class, () -> decoder.decode(buffer("*1\r\n$4\r\nPINGxx")));
    }

    @Test
    void rejectsHugeLengthsWithoutWaitingForTheData() {
        // The bytes after the length have not arrived, yet the length alone is already invalid.
        assertThrows(ProtocolException.class, () -> decoder.decode(buffer("*1\r\n$99999999999\r\n")));
        assertThrows(ProtocolException.class, () -> decoder.decode(buffer("*99999999999\r\n")));
    }

    @Test // simulates exactly what the event loop does: put, flip, decode, compact
    void reassemblesCommandsFedInRandomSizedChunks() throws Exception {
        Random random = new Random(5);
        List<String> expected = new ArrayList<>();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int i = 0; i < 200; i++) {
            String arg = "value-" + i + "-" + "x".repeat(random.nextInt(50));
            expected.add(arg);
            stream.writeBytes(bytes("*2\r\n$4\r\nECHO\r\n$" + arg.length() + "\r\n" + arg + "\r\n"));
        }
        byte[] all = stream.toByteArray();

        ByteBuffer buf = ByteBuffer.allocate(256); // in WRITE mode, like Connection.in
        List<String> decoded = new ArrayList<>();
        int offset = 0;
        while (offset < all.length) {
            int chunk = Math.min(1 + random.nextInt(20), all.length - offset);
            buf.put(all, offset, chunk);
            offset += chunk;

            buf.flip();
            List<byte[]> command;
            while ((command = decoder.decode(buf)) != null) {
                decoded.add(text(command.get(1)));
            }
            buf.compact();
        }

        assertEquals(expected, decoded);
    }
}