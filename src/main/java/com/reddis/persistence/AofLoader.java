package com.reddis.persistence;

import com.reddis.command.CommandRouter;
import com.reddis.protocol.ProtocolException;
import com.reddis.protocol.RespDecoder;
import com.reddis.protocol.RespValue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Replays an append-only file through the command router, streaming it in chunks. */
public final class AofLoader {

    /**
     * @param commands      number of commands replayed
     * @param validBytes    length of the file up to the end of the last COMPLETE command
     * @param truncatedTail true if bytes followed that never formed a complete command (a crash mid-write)
     */
    public record LoadResult(long commands, long validBytes, boolean truncatedTail) { }

    private static final int INITIAL_BUFFER = 64 * 1024;
    private static final int MAX_BUFFER = 600 * 1024 * 1024; // a 512 MB bulk string plus framing

    private AofLoader() { }

    /**
     * @throws IOException if the log is corrupted (invalid bytes where a command should start). A torn tail at the
     *                     very end is NOT an error: it is reported in the result.
     */
    public static LoadResult load(Path file, CommandRouter router) throws IOException {
        RespDecoder decoder = new RespDecoder();
        ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER); // always in WRITE mode between iterations
        long consumed = 0;
        long commands = 0;

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            boolean endOfFile = false;
            while (!endOfFile) {
                endOfFile = channel.read(buffer) < 0;
                buffer.flip();
                try {
                    while (true) {
                        int before = buffer.position();
                        List<byte[]> command = decoder.decode(buffer);
                        if (command == null) {
                            break; // incomplete: wait for more bytes (or the end of the file)
                        }
                        consumed += buffer.position() - before;
                        if (command.isEmpty()) {
                            continue;
                        }
                        RespValue reply = router.route(command);
                        commands++;
                        if (reply instanceof RespValue.RespError error) {
                            System.err.println("AOF replay: a command failed: " + error.message());
                        }
                    }
                } catch (ProtocolException e) {
                    throw new IOException("AOF is corrupted at byte " + consumed + ": " + e.getMessage(), e);
                }
                buffer.compact();

                if (!endOfFile && !buffer.hasRemaining()) { // one command is bigger than the buffer: grow it
                    if (buffer.capacity() >= MAX_BUFFER) {
                        throw new IOException("AOF command larger than " + MAX_BUFFER + " bytes at byte " + consumed);
                    }
                    ByteBuffer bigger = ByteBuffer.allocate(Math.min(MAX_BUFFER, buffer.capacity() * 2));
                    buffer.flip();
                    bigger.put(buffer);
                    buffer = bigger;
                }
            }
            boolean truncated = buffer.position() > 0; // leftover bytes at the end of the file
            return new LoadResult(commands, consumed, truncated);
        }
    }
}