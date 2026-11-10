package com.reddis.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Crash-safe file replacement: temp file, fsync, atomic rename, fsync the directory. */
final class AtomicFiles {

    private AtomicFiles() { }

    /** Writes data to target so that, whatever happens, target is either the old content or the new content. */
    static void write(Path target, byte[] data) throws IOException {
        Path temp = tempFor(target);
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true); // the data is on the disk BEFORE it becomes visible under the real name
        }
        replace(temp, target);
    }

    static Path tempFor(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    /** Atomically moves temp over target, then makes the rename itself durable. */
    static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        syncDirectory(target.toAbsolutePath().getParent());
    }

    /** Best effort: some platforms (Windows) cannot open a directory for fsync. */
    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // nothing more we can do
        }
    }
}