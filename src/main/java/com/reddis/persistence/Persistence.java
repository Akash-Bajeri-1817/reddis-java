package com.reddis.persistence;

import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespValue;
import com.reddis.server.CommandHooks;
import com.reddis.storage.DataStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.io.UncheckedIOException;

/**
 * Ties snapshots and the append-only file together and plugs into the event loop as CommandHooks.
 *
 * THREADING: load(), the hooks, cron(), save(), startBackgroundSave() and rewriteAof() run on the event-loop
 * thread (the only thread that touches the store). Only the disk write of a BGSAVE runs elsewhere, and it works
 * on an immutable byte array.
 */
public final class Persistence implements CommandHooks {

    private final PersistenceConfig config;
    private final DataStore store;
    private final CommandRouter router;
    private final CommandPropagator propagator;

    private final AtomicLong dirty = new AtomicLong();                       // writes since the last snapshot
    private final AtomicBoolean backgroundSaveRunning = new AtomicBoolean();
    private volatile long lastSaveMillis = System.currentTimeMillis();
    private final ExecutorService saver = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "reddis-bgsave");
        thread.setDaemon(true);
        return thread;
    });
    private AofWriter aof; // null when the append-only file is disabled

    public Persistence(PersistenceConfig config, DataStore store, CommandRouter router) {
        this.config = config;
        this.store = store;
        this.router = router;
        this.propagator = new CommandPropagator(store);
    }

    // ------------------------------------------------------------- startup

    /**
     * Restores the dataset. Call once, BEFORE accepting clients.
     * Order: AOF (if enabled and present) > snapshot. Throws if a file is corrupted: refusing to start is safer
     * than serving partial data.
     */
    public void load() throws IOException {
        Files.createDirectories(config.dir());
        Path rdb = config.rdbPath();
        Path aofPath = config.aofPath();
        boolean aofExisted = Files.exists(aofPath);

        if (config.appendOnly() && aofExisted) {
            AofLoader.LoadResult result = AofLoader.load(aofPath, router);
            if (result.truncatedTail()) {
                try (FileChannel channel = FileChannel.open(aofPath, StandardOpenOption.WRITE)) {
                    channel.truncate(result.validBytes());
                }
                System.out.println("AOF: dropped an incomplete last command (crash during a write?); "
                        + "file repaired to " + result.validBytes() + " bytes");
            }
            System.out.println("AOF: replayed " + result.commands() + " commands");
        } else if (Files.exists(rdb)) {
            int entries = SnapshotCodec.load(rdb, store);
            System.out.println("Snapshot: loaded " + entries + " entries from " + rdb);
        }

        if (config.appendOnly()) {
            aof = new AofWriter(aofPath, config.fsync());
            if (!aofExisted && store.size() > 0) {
                aof.rewrite(store); // bootstrap the log from the snapshot we just loaded
                System.out.println("AOF: created from the loaded snapshot");
            }
        }
        lastSaveMillis = System.currentTimeMillis();
    }

    // ------------------------------------------------------- event-loop hooks

    @Override
    public void afterCommand(List<byte[]> command, RespValue reply) throws IOException {
        List<List<byte[]>> records = propagator.propagate(command, reply);
        if (records.isEmpty()) {
            return;
        }
        dirty.incrementAndGet();
        if (aof != null) {
            for (List<byte[]> record : records) {
                aof.append(record);
            }
        }
    }

    /** Once per batch, before replies go out: with fsync=always a client's OK means "it is on the disk". */
    @Override
    public void beforeReplies() throws IOException {
        if (aof != null) {
            aof.flush();
        }
    }

    /**
     * Called when the memory guard evicts a key. The eviction must be logged as DEL: otherwise replaying the log
     * after a restart would bring the evicted key back and exceed the memory limit again.
     */
    public void recordEviction(byte[] key) {
        dirty.incrementAndGet();
        if (aof != null) {
            try {
                aof.append(List.of(AofFormat.ascii("DEL"), key));
            } catch (IOException e) {
                throw new UncheckedIOException(e); // cannot happen: the record is only encoded into memory here
            }
        }
    }

    /**
     * The whole dataset was replaced (a replica finished a full resync): the old log no longer describes it, so it is
     * rewritten from the new data, and the data counts as unsaved for the snapshot rules.
     */
    public void onDatasetReplaced() {
        dirty.incrementAndGet();
        if (aof != null) {
            try {
                aof.rewrite(store);
            } catch (IOException e) {
                System.err.println("AOF rewrite after the resync failed: " + e);
            }
        }
    }

    /** Called periodically by the event loop. */
    public void cron() {
        long interval = config.saveIntervalSeconds();
        if (interval > 0
                && dirty.get() > 0
                && !backgroundSaveRunning.get()
                && System.currentTimeMillis() - lastSaveMillis >= interval * 1000) {
            try {
                startBackgroundSave();
            } catch (IOException e) {
                System.err.println("Automatic snapshot failed: " + e);
            }
        }
    }

    // ---------------------------------------------------------------- commands

    /** SAVE: capture and write on the calling thread. */
    public void save() throws IOException {
        if (backgroundSaveRunning.get()) {
            throw new IllegalStateException("Background save already in progress");
        }
        long snapshotDirty = dirty.getAndSet(0);
        try {
            byte[] snapshot = SnapshotCodec.serialize(store);
            AtomicFiles.write(config.rdbPath(), snapshot);
            lastSaveMillis = System.currentTimeMillis();
        } catch (IOException | RuntimeException e) {
            dirty.addAndGet(snapshotDirty); // the data is still unsaved
            throw e;
        }
    }

    /**
     * BGSAVE: capture a consistent snapshot NOW (on the calling thread, since only it may read the store), then
     * write it to disk on a background thread.
     *
     * @return false if a background save is already running
     */
    public boolean startBackgroundSave() throws IOException {
        if (!backgroundSaveRunning.compareAndSet(false, true)) {
            return false;
        }
        long snapshotDirty = dirty.getAndSet(0);
        byte[] snapshot;
        try {
            snapshot = SnapshotCodec.serialize(store);
        } catch (IOException | RuntimeException e) {
            dirty.addAndGet(snapshotDirty);
            backgroundSaveRunning.set(false);
            throw e;
        }

        saver.execute(() -> {
            try {
                AtomicFiles.write(config.rdbPath(), snapshot);
                lastSaveMillis = System.currentTimeMillis();
                System.out.println("Background save finished (" + snapshot.length + " bytes)");
            } catch (IOException | RuntimeException e) {
                dirty.addAndGet(snapshotDirty);
                System.err.println("Background save failed: " + e);
            } finally {
                backgroundSaveRunning.set(false);
            }
        });
        return true;
    }

    /** REWRITEAOF: compact the append-only file. */
    public void rewriteAof() throws IOException {
        if (aof == null) {
            throw new IllegalStateException("AOF is not enabled");
        }
        aof.rewrite(store);
    }

    // ------------------------------------------------------------------ queries

    public long lastSaveSeconds() {
        return lastSaveMillis / 1000;
    }

    public long unsavedChanges() {
        return dirty.get();
    }

    public boolean isBackgroundSaveRunning() {
        return backgroundSaveRunning.get();
    }

    public boolean isAofEnabled() {
        return aof != null;
    }

    // ----------------------------------------------------------------- shutdown

    /** Call after the event loop has stopped: finishes a running BGSAVE, closes the log, takes a final snapshot. */
    public void close() {
        saver.shutdown();
        try {
            saver.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (aof != null) {
                aof.close();
            }
            if (config.saveIntervalSeconds() > 0 && dirty.get() > 0) {
                save();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Persistence shutdown failed: " + e);
        }
    }
}