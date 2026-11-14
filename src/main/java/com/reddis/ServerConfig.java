package com.reddis;

import com.reddis.eviction.EvictionPolicy;
import com.reddis.eviction.MemoryConfig;
import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.replication.ReplicationConfig;

import java.nio.file.Path;

/** Command-line configuration. */
public record ServerConfig(int port, boolean blocking, PersistenceConfig persistence, MemoryConfig memory,
                           ReplicationConfig replication) {

    public static final String USAGE = """
            Usage: Main [port] [options]
              --blocking                         legacy thread-per-client server (no persistence, maxmemory,
                                                 pub/sub, transactions or replication)
              --dir <path>                       directory for dump.rdb and appendonly.aof (default: data)
              --appendonly yes|no                enable the append-only file (default: no)
              --appendfsync always|everysec|no   AOF fsync policy (default: everysec)
              --save-interval <seconds>          automatic background snapshot if data changed (default: 0 = off)
              --maxmemory <size>                 memory limit, e.g. 100mb (default: 0 = unlimited)
              --maxmemory-policy <policy>        noeviction | allkeys-lru | volatile-lru | allkeys-lfu | volatile-lfu
                                                 | allkeys-random | volatile-random | volatile-ttl (default: noeviction)
              --maxmemory-samples <n>            keys sampled per eviction (default: 5)
              --replicaof <host> <port>          start as a replica of that master
              --repl-backlog-size <size>         replication backlog kept by a master (default: 1mb)""";

    public static ServerConfig parse(String... args) {
        int port = 6379;
        boolean blocking = false;
        Path dir = Path.of("data");
        boolean appendOnly = false;
        FsyncPolicy fsync = FsyncPolicy.EVERYSEC;
        long saveInterval = 0;
        long maxMemory = 0;
        EvictionPolicy policy = EvictionPolicy.NOEVICTION;
        int samples = 5;
        String masterHost = null;
        int masterPort = 0;
        long backlogBytes = ReplicationConfig.DEFAULT_BACKLOG_BYTES;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--blocking" -> blocking = true;
                case "--dir" -> {
                    dir = Path.of(valueAfter(args, i));
                    i++;
                }
                case "--appendonly" -> {
                    appendOnly = parseYesNo(valueAfter(args, i));
                    i++;
                }
                case "--appendfsync" -> {
                    fsync = FsyncPolicy.parse(valueAfter(args, i));
                    i++;
                }
                case "--save-interval" -> {
                    saveInterval = Long.parseLong(valueAfter(args, i));
                    i++;
                }
                case "--maxmemory" -> {
                    maxMemory = MemoryConfig.parseBytes(valueAfter(args, i));
                    i++;
                }
                case "--maxmemory-policy" -> {
                    policy = EvictionPolicy.parse(valueAfter(args, i));
                    i++;
                }
                case "--maxmemory-samples" -> {
                    samples = Integer.parseInt(valueAfter(args, i));
                    i++;
                }
                case "--replicaof" -> {
                    if (i + 2 >= args.length) {
                        throw new IllegalArgumentException("--replicaof needs a host and a port");
                    }
                    masterHost = args[i + 1];
                    masterPort = Integer.parseInt(args[i + 2]);
                    i += 2;
                }
                case "--repl-backlog-size" -> {
                    backlogBytes = MemoryConfig.parseBytes(valueAfter(args, i));
                    i++;
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    port = Integer.parseInt(arg);
                }
            }
        }
        return new ServerConfig(port, blocking,
                new PersistenceConfig(dir, appendOnly, fsync, saveInterval),
                new MemoryConfig(maxMemory, policy, samples),
                new ReplicationConfig(masterHost, masterPort, backlogBytes));
    }

    private static String valueAfter(String[] args, int optionIndex) {
        if (optionIndex + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[optionIndex]);
        }
        return args[optionIndex + 1];
    }

    private static boolean parseYesNo(String text) {
        if (text.equalsIgnoreCase("yes")) {
            return true;
        }
        if (text.equalsIgnoreCase("no")) {
            return false;
        }
        throw new IllegalArgumentException("Expected yes or no, not: " + text);
    }
}