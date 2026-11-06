package com.reddis.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads client commands from a byte stream.
 * A command is a RESP array of bulk strings: *N\r\n then N x ($len\r\n<bytes>\r\n).
 * Works on raw bytes (never Strings) so binary data is never corrupted.
 * Pass in a BufferedInputStream for performance.
 */
public class RespParser {

    private static final int MAX_ARRAY_LENGTH = 1_024 * 1_024;
    private static final int MAX_BULK_LENGTH = 512 * 1_024 * 1_024;

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in;
    }

    /**
     * Reads the next command.
     *
     * @return the command parts (name first), an empty list for an empty array,
     *         or null if the client closed the connection cleanly between commands
     * @throws ProtocolException if the bytes are not valid RESP
     * @throws EOFException      if the connection closes in the middle of a command
     */
    public List<byte[]> readCommand() throws IOException {
        int prefix = in.read(); // blocks until a byte arrives
        if (prefix == -1) {
            return null;
        }
        if (prefix != '*') {
            throw new ProtocolException("expected '*' but got '" + (char) prefix + "'");
        }

        long count = readNumber();
        if (count < 0) {
            return Collections.emptyList(); // *-1 is a null array
        }
        if (count > MAX_ARRAY_LENGTH) {
            throw new ProtocolException("array too large: " + count);
        }

        // Don't trust the client's count for allocation: cap the initial capacity.
        List<byte[]> parts = new ArrayList<>(Math.min((int) count, 16));
        for (int i = 0; i < count; i++) {
            parts.add(readBulkString());
        }
        return parts;
    }

    private byte[] readBulkString() throws IOException {
        int prefix = in.read();
        if (prefix == -1) {
            throw new EOFException("connection closed in the middle of a command");
        }
        if (prefix != '$') {
            throw new ProtocolException("expected '$' but got '" + (char) prefix + "'");
        }

        long length = readNumber();
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new ProtocolException("invalid bulk string length: " + length);
        }

        // readNBytes keeps reading until it has exactly 'length' bytes or hits end of stream.
        // This is what makes us robust against TCP splitting data across several reads.
        byte[] data = in.readNBytes((int) length);
        if (data.length < length) {
            throw new EOFException("connection closed in the middle of a bulk string");
        }
        expectCrLf();
        return data;
    }

    /** Reads an optionally negative decimal number followed by \r\n. */
    private long readNumber() throws IOException {
        boolean negative = false;
        boolean sawDigit = false;
        long result = 0;

        int b = in.read();
        if (b == '-') {
            negative = true;
            b = in.read();
        }
        while (b != '\r') {
            if (b == -1) {
                throw new EOFException("connection closed while reading a number");
            }
            if (b < '0' || b > '9') {
                throw new ProtocolException("invalid character in number: '" + (char) b + "'");
            }
            result = result * 10 + (b - '0');
            if (result > MAX_BULK_LENGTH) {
                throw new ProtocolException("number too large");
            }
            sawDigit = true;
            b = in.read();
        }
        if (!sawDigit) {
            throw new ProtocolException("missing number");
        }
        int lf = in.read();
        if (lf != '\n') {
            throw new ProtocolException("expected LF after CR");
        }
        return negative ? -result : result;
    }

    private void expectCrLf() throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr == -1 || lf == -1) {
            throw new EOFException("connection closed while expecting CRLF");
        }
        if (cr != '\r' || lf != '\n') {
            throw new ProtocolException("expected CRLF after bulk string");
        }
    }
}