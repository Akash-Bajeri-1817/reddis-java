package com.reddis.persistence;

import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import com.reddis.support.StoreDump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCodecTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(1_000_000L);

    private Path write(byte[] content) throws IOException {
        Path file = dir.resolve("dump.rdb");
        Files.write(file, content);
        return file;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripPreservesEveryTypeValueAndDeadline() throws Exception {
        DataStore source = new DataStore(clock);
        StoreDump.populate(source);

        Path file = write(SnapshotCodec.serialize(source));
        DataStore target = new DataStore(clock);
        int entries = SnapshotCodec.load(file, target);

        assertEquals(source.size(), entries);
        assertEquals(StoreDump.of(source), StoreDump.of(target));
    }

    @Test
    void anEmptyStoreRoundTrips() throws Exception {
        Path file = write(SnapshotCodec.serialize(new DataStore(clock)));
        DataStore target = new DataStore(clock);
        assertEquals(0, SnapshotCodec.load(file, target));
        assertEquals(0, target.size());
    }

    @Test // AC-3
    void expiredKeysAreNeitherSavedNorRestored() throws Exception {
        DataStore source = new DataStore(clock);
        source.set(b("gone"), b("v"), 100);
        source.set(b("later"), b("v"), 500);
        source.set(b("forever"), b("v"));
        clock.advance(100); // "gone" has expired by save time
        byte[] bytes = SnapshotCodec.serialize(source);

        // "later" expires between saving and loading
        DataStore target = new DataStore(new MutableClock(1_000_000L + 600));
        SnapshotCodec.load(write(bytes), target);

        assertEquals(1, target.size());
        assertArrayEquals(b("v"), target.get(b("forever")));
    }

    @Test // AC-2
    void aCorruptedByteIsDetected() throws Exception {
        DataStore source = new DataStore(clock);
        StoreDump.populate(source);
        byte[] good = SnapshotCodec.serialize(source);

        for (int index : new int[]{10, good.length / 2, good.length - 12}) {
            byte[] bad = good.clone();
            bad[index] ^= 0x55;
            Path file = write(bad);
            assertThrows(IOException.class, () -> SnapshotCodec.load(file, new DataStore(clock)),
                    "flipped byte at " + index);
        }
    }

    @Test // AC-2
    void aTruncatedFileIsRejected() throws Exception {
        DataStore source = new DataStore(clock);
        StoreDump.populate(source);
        byte[] good = SnapshotCodec.serialize(source);

        Path file = write(Arrays.copyOf(good, good.length - 10));
        assertThrows(IOException.class, () -> SnapshotCodec.load(file, new DataStore(clock)));
    }

    @Test
    void aFileWithTheWrongMagicIsRejected() throws Exception {
        Path file = write(b("NOTADUMPFILE-at-all"));
        assertThrows(IOException.class, () -> SnapshotCodec.load(file, new DataStore(clock)));
    }

    @Test
    void dataAfterTheChecksumIsRejected() throws Exception {
        byte[] good = SnapshotCodec.serialize(new DataStore(clock));
        byte[] padded = Arrays.copyOf(good, good.length + 1);
        Path file = write(padded);
        assertThrows(IOException.class, () -> SnapshotCodec.load(file, new DataStore(clock)));
    }

    @Test // AC-2: a corrupt length must not make us allocate gigabytes
    void aHugeLengthIsRejectedWithoutAllocatingIt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(b("REDDIS"));
        out.writeByte(1);       // version
        out.writeByte(0);       // string record
        out.writeInt(2_000_000_000); // key length: far larger than the file

        Path file = write(bytes.toByteArray());
        IOException error = assertThrows(IOException.class, () -> SnapshotCodec.load(file, new DataStore(clock)));
        assertTrue(error.getMessage().contains("invalid length"), error.getMessage());
    }
}