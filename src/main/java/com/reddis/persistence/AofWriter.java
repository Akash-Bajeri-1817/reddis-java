package com.reddis.persistence;

import com.reddis.storage.DataStore;
import com.reddis.protocol.RespWriter;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Appends commands to the append-only file.
 *
 * append() only encodes into memory. flush() (called once per batch of commands, before the replies are sent)
 * does the write, and with FsyncPolicy.ALWAYS also the fsync: one fsync covers every command of the batch
 * ("group commit"). With EVERYSEC a daemon thread fsyncs once a second.
 *
 * append(), flush() and rewrite() must be called from one thread (the event loop).
 */
public final class AofWriter implements Closeable {

    private final Path path;
    private final FsyncPolicy policy;
    private volatile FileChannel channel; // swapped by rewrite(); read by the fsync thread
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final RespWriter encoder = new RespWriter(pending);
    private volatile boolean unsynced;
    private final ScheduledExecutorService syncThread;

    public AofWriter(Path path, FsyncPolicy policy) throws IOException {
        this.path = path;
        this.policy = policy;
        this.channel = openForAppend(path);

        if (policy == FsyncPolicy.EVERYSEC) {
            syncThread = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "reddis-aof-fsync");
                thread.setDaemon(true);
                return thread;
            });
            syncThread.scheduleWithFixedDelay(this::syncIfNeeded, 1, 1, TimeUnit.SECONDS);
        } else {
            syncThread = null;
        }
    }

    private static FileChannel openForAppend(Path path) throws IOException {
        return FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** Queues one command (in memory only, until flush()). */
    public void append(List<byte[]> command) throws IOException {
        AofFormat.encode(encoder, command);
    }

    /** Writes everything queued so far to the file (and fsyncs it if the policy is ALWAYS). */
    public void flush() throws IOException {
        if (pending.size() == 0) {
            return;
        }
        ByteBuffer data = ByteBuffer.wrap(pending.toByteArray());
        pending.reset();

        FileChannel current = channel;
        while (data.hasRemaining()) {
            current.write(data);
        }
        if (policy == FsyncPolicy.ALWAYS) {
            current.force(false); // fdatasync: the content is on stable storage before we reply
        } else {
            unsynced = true;
        }
    }

    /** Runs on the background thread (EVERYSEC only). */
    private void syncIfNeeded() {
        if (!unsynced) {
            return;
        }
        unsynced = false;
        try {
            channel.force(false);
        } catch (ClosedChannelException e) {
            // a rewrite swapped the channel; the new one will be synced next round
        } catch (IOException e) {
            unsynced = true;
            System.err.println("AOF fsync failed: " + e);
        }
    }

    /**
     * Replaces the log with the minimal commands that rebuild the store's current dataset (compaction).
     * Crash-safe: the new log is built in a temp file, fsynced, then atomically renamed over the old one.
     * Runs on the calling thread, so the caller is blocked for the duration.
     */
    public void rewrite(DataStore store) throws IOException {
        flush();
        Path temp = AtomicFiles.tempFor(path);
        try (FileChannel tempChannel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            OutputStream out = new BufferedOutputStream(Channels.newOutputStream(tempChannel), 1 << 16);
            AofFormat.writeDataset(store, out);
            out.flush();
            tempChannel.force(true);
        }

        channel.close(); // some platforms cannot replace a file that is still open
        try {
            AtomicFiles.replace(temp, path);
        } catch (IOException e) {
            channel = openForAppend(path); // keep appending to the old log
            throw e;
        }
        channel = openForAppend(path);
        unsynced = false;
    }

    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        if (syncThread != null) {
            syncThread.shutdownNow();
        }
        try {
            flush();
            channel.force(false);
        } finally {
            channel.close();
        }
    }
}