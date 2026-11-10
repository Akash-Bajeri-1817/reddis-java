package com.reddis.persistence;

import java.nio.file.Path;

/**
 * @param dir                 directory holding dump.rdb and appendonly.aof
 * @param appendOnly          whether the append-only file is enabled
 * @param fsync               AOF fsync policy
 * @param saveIntervalSeconds take a background snapshot this often if anything changed; 0 = never automatically
 */
public record PersistenceConfig(Path dir, boolean appendOnly, FsyncPolicy fsync, long saveIntervalSeconds) {

    public static PersistenceConfig defaults() {
        return new PersistenceConfig(Path.of("data"), false, FsyncPolicy.EVERYSEC, 0);
    }

    public Path rdbPath() {
        return dir.resolve("dump.rdb");
    }

    public Path aofPath() {
        return dir.resolve("appendonly.aof");
    }
}