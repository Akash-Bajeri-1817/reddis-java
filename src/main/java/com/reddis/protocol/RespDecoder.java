package com.reddis.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Incremental RESP command decoder for NIO. Unlike RespParser (which blocks until a whole command has
 * arrived), this works on whatever bytes are in a buffer right now.
 *
 * CONTRACT of decode(buf), with buf in READ mode (position..limit holds unread bytes):
 *  - a complete command is present: returns its parts and advances buf.position() past it
 *  - the command is incomplete: returns null and leaves buf.position() EXACTLY as it was
 *  - the bytes violate RESP: throws ProtocolException
 * Empty arrays (*0 and *-1) are consumed and returned as an empty list.
 */
public class RespDecoder {

    // same limits as RespParser: a bad length is rejected immediately, without waiting for the data
    private static final int MAX_ARRAY_LENGTH = 1_024 * 1_024;
    private static final int MAX_BULK_LENGTH = 512 * 1_024 * 1_024;

    private static final long INCOMPLETE = Long.MIN_VALUE; // never a valid length

    /** A read position that only becomes the buffer's real position once a command is complete. */
    private static final class Cursor {
        final ByteBuffer buf;
        final int limit;
        int pos;

        Cursor(ByteBuffer buf) {
            this.buf = buf;
            this.pos = buf.position();
            this.limit = buf.limit();
        }
    }

    public List<byte[]> decode(ByteBuffer buf) throws ProtocolException {
        Cursor c = new Cursor(buf);
        if (c.pos >= c.limit) {
            return null;
        }

        byte first = buf.get(c.pos);
        if (first != '*') {
            throw new ProtocolException("expected '*' but got '" + printable(first) + "'");
        }
        c.pos++;

        long count = readNumber(c);
        if (count == INCOMPLETE) {
            return null;
        }
        if (count <= 0) { // *0 and *-1: nothing to execute
            buf.position(c.pos);
            return Collections.emptyList();
        }
        if (count > MAX_ARRAY_LENGTH) {
            throw new ProtocolException("array too large: " + count);
        }

        List<byte[]> parts = new ArrayList<>((int) Math.min(count, 16));
        for (long i = 0; i < count; i++) {
            if (c.pos >= c.limit) {
                return null;
            }
            byte prefix = buf.get(c.pos);
            if (prefix != '$') {
                throw new ProtocolException("expected '$' but got '" + printable(prefix) + "'");
            }
            c.pos++;

            long length = readNumber(c);
            if (length == INCOMPLETE) {
                return null;
            }
            if (length < 0 || length > MAX_BULK_LENGTH) {
                throw new ProtocolException("invalid bulk string length: " + length);
            }
            if ((long) c.limit - c.pos < length + 2) { // the data plus its trailing CRLF
                return null;
            }

            byte[] data = new byte[(int) length];
            buf.get(c.pos, data); // absolute read: does not move the buffer's position
            c.pos += (int) length;
            if (buf.get(c.pos) != '\r' || buf.get(c.pos + 1) != '\n') {
                throw new ProtocolException("expected CRLF after bulk string");
            }
            c.pos += 2;
            parts.add(data);
        }

        buf.position(c.pos); // only now do we "consume" the command
        return parts;
    }

    /** Reads an optionally negative decimal number followed by CRLF. Returns INCOMPLETE if bytes are missing. */
    private static long readNumber(Cursor c) throws ProtocolException {
        if (c.pos >= c.limit) {
            return INCOMPLETE;
        }
        boolean negative = false;
        boolean sawDigit = false;
        long result = 0;

        byte b = c.buf.get(c.pos);
        if (b == '-') {
            negative = true;
            c.pos++;
            if (c.pos >= c.limit) {
                return INCOMPLETE;
            }
            b = c.buf.get(c.pos);
        }
        while (b != '\r') {
            if (b < '0' || b > '9') {
                throw new ProtocolException("invalid character in number: '" + printable(b) + "'");
            }
            result = result * 10 + (b - '0');
            if (result > MAX_BULK_LENGTH) {
                throw new ProtocolException("number too large"); // rejected before all the digits even arrive
            }
            sawDigit = true;
            c.pos++;
            if (c.pos >= c.limit) {
                return INCOMPLETE;
            }
            b = c.buf.get(c.pos);
        }
        if (!sawDigit) {
            throw new ProtocolException("missing number");
        }
        if (c.pos + 1 >= c.limit) { // we are at the CR; the LF has not arrived yet
            return INCOMPLETE;
        }
        if (c.buf.get(c.pos + 1) != '\n') {
            throw new ProtocolException("expected LF after CR");
        }
        c.pos += 2;
        return negative ? -result : result;
    }

    private static char printable(byte b) {
        return (char) (b & 0xFF);
    }
}