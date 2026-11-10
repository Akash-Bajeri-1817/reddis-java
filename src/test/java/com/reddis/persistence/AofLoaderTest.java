package com.reddis.persistence;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespWriter;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import com.reddis.support.StoreDump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AofLoaderTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(1_000_000L);

    /** The AOF bytes for one command. */
    private static byte[] record(String... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        AofFormat.encode(new RespWriter(bytes), command);
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            bytes.writeBytes(chunk);
        }
        return bytes.toByteArray();
    }

    private Path write(byte[] content) throws IOException {
        Path file = dir.resolve("appendonly.aof");
        Files.write(file, content);
        return file;
    }

    @Test
    void replaysCommandsInOrder() throws Exception {
        Path file = write(concat(
                record("SET", "a", "1"),
                record("RPUSH", "l", "x", "y"),
                record("DEL", "a"),
                record("HSET", "h", "f", "v")));

        DataStore store = new DataStore(clock);
        CommandRouter router = CommandRegistry.standard(store);
        AofLoader.LoadResult result = AofLoader.load(file, router);

        assertEquals(4, result.commands());
        assertFalse(result.truncatedTail());
        assertEquals(Files.size(file), result.validBytes());
        assertNull(store.get("a".getBytes(StandardCharsets.UTF_8)));
        assertEquals("list:x,y", StoreDump.of(store).get("l").split("\\|")[0]);
        assertEquals("hash:f=v", StoreDump.of(store).get("h").split("\\|")[0]);
    }

    @Test // AC-7
    void aTornTailIsReportedWithTheLengthOfTheValidPart() throws Exception {
        byte[] first = record("SET", "a", "1");
        byte[] second = record("SET", "b", "2");
        byte[] third = record("SET", "c", "3");
        Path file = write(concat(first, second, Arrays.copyOf(third, 10))); // the last command is cut off

        AofLoader.LoadResult result = AofLoader.load(file, CommandRegistry.standard(new DataStore(clock)));

        assertEquals(2, result.commands());
        assertEquals(first.length + second.length, result.validBytes());
        assertTrue(result.truncatedTail());
    }

    @Test // AC-7
    void corruptionInTheMiddleIsRejected() throws Exception {
        Path file = write(concat(
                record("SET", "a", "1"),
                "garbage\r\n".getBytes(StandardCharsets.UTF_8),
                record("SET", "b", "2")));

        IOException error = assertThrows(IOException.class,
                () -> AofLoader.load(file, CommandRegistry.standard(new DataStore(clock))));
        assertTrue(error.getMessage().contains("corrupted"), error.getMessage());
    }

    @Test
    void anEmptyFileLoadsNothing() throws Exception {
        AofLoader.LoadResult result = AofLoader.load(write(new byte[0]),
                CommandRegistry.standard(new DataStore(clock)));
        assertEquals(0, result.commands());
        assertEquals(0, result.validBytes());
        assertFalse(result.truncatedTail());
    }

    @Test // AC-8: the generated minimal log replays to exactly the same data
    void aRewrittenDatasetReplaysToTheSameData() throws Exception {
        DataStore source = new DataStore(clock);
        StoreDump.populate(source);

        Path file = dir.resolve("rewritten.aof");
        try (OutputStream out = Files.newOutputStream(file)) {
            AofFormat.writeDataset(source, out);
        }

        DataStore target = new DataStore(clock);
        AofLoader.load(file, CommandRegistry.standard(target));

        assertEquals(StoreDump.of(source), StoreDump.of(target)); // all five types, 200-element list, deadline
    }
}