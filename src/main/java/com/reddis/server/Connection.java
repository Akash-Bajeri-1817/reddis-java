package com.reddis.server;

import com.reddis.protocol.RespWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Everything the event loop remembers about one client: its channel, a buffer of bytes received but not yet
 * decoded, and a buffer of reply bytes not yet sent. Only ever touched by the event-loop thread.
 *
 * Both buffers are kept in WRITE mode between events (position = end of the data they hold).
 */
final class Connection {

    static final int INITIAL_BUFFER = 16 * 1024;
    static final int MAX_INPUT = 64 * 1024 * 1024;   // a single command (or unread backlog) may not exceed this
    static final int MAX_OUTPUT = 64 * 1024 * 1024;  // unsent replies may not exceed this
    static final int OUTPUT_HIGH_WATER = 1024 * 1024; // above this we stop reading from the client

    final SocketChannel channel;
    SelectionKey key;                       // set right after registration
    ByteBuffer in = ByteBuffer.allocate(INITIAL_BUFFER);
    ByteBuffer out = ByteBuffer.allocate(INITIAL_BUFFER);
    final RespWriter writer = new RespWriter(new OutputBuffer());
    boolean closeAfterFlush;                // stop reading; close once the pending reply is sent

    Connection(SocketChannel channel) {
        this.channel = channel;
    }

    boolean hasPendingOutput() {
        return out.position() > 0;
    }

    /** Doubles the input buffer, keeping its unread bytes. @return false if it is already at the limit */
    boolean growInput() {
        if (in.capacity() >= MAX_INPUT) {
            return false;
        }
        int newCapacity = (int) Math.min((long) MAX_INPUT, in.capacity() * 2L);
        ByteBuffer bigger = ByteBuffer.allocate(newCapacity);
        in.flip();
        bigger.put(in);
        in = bigger;
        return true;
    }

    /** Lets RespWriter append straight into this connection's output buffer, growing it as needed. */
    private final class OutputBuffer extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            ensureRoom(1);
            out.put((byte) b);
        }

        @Override
        public void write(byte[] src, int offset, int length) throws IOException {
            ensureRoom(length);
            out.put(src, offset, length);
        }

        private void ensureRoom(int extra) throws IOException {
            if (out.remaining() >= extra) {
                return;
            }
            long needed = (long) out.position() + extra;
            if (needed > MAX_OUTPUT) {
                throw new IOException("output buffer limit exceeded"); // the loop closes this connection
            }
            int newCapacity = (int) Math.min((long) MAX_OUTPUT, Math.max(needed, out.capacity() * 2L));
            ByteBuffer bigger = ByteBuffer.allocate(newCapacity);
            out.flip();
            bigger.put(out);
            out = bigger;
        }
    }
}