package com.reddis.storage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs DataStore.purgeExpired() on a background daemon thread at a fixed interval. */
public class ExpiryPurger implements AutoCloseable {

    private final DataStore store;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler;

    public ExpiryPurger(DataStore store, long intervalMillis) {
        this.store = store;
        this.intervalMillis = intervalMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "reddis-expiry-purger");
            thread.setDaemon(true); // never keeps the JVM alive
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::purgeSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** If a run threw, the scheduler would silently cancel ALL future runs. So we never let it throw. */
    private void purgeSafely() {
        try {
            store.purgeExpired();
        } catch (RuntimeException e) {
            System.err.println("Expiry purge failed: " + e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}