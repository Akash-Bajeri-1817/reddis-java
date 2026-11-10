package com.reddis;

import com.reddis.persistence.FsyncPolicy;
import com.reddis.persistence.PersistenceConfig;

import java.nio.file.Path;

/** Command-line configuration. */
public record ServerConfig(int port, boolean blocking, PersistenceConfig persistence) {

    public static final String USAGE = """
            Usage: Main [port] [options]
              --blocking                    legacy thread-per-client server (no persistence)
              --dir <path>                  directory for dump.rdb and appendonly.aof (default: data)
              --appendonly yes|no           enable the append-only file (default: no)
              --appendfsync always|everysec|no   AOF fsync policy (default: everysec)
              --save-interval <seconds>     automatic background snapshot if data changed (default: 0 = off)""";

    public static ServerConfig parse(String... args) {
        int port = 6379;
        boolean blocking = false;
        Path dir = Path.of("data");
        boolean appendOnly = false;
        FsyncPolicy fsync = FsyncPolicy.EVERYSEC;
        long saveInterval = 0;

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
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    port = Integer.parseInt(arg);
                }
            }
        }
        return new ServerConfig(port, blocking, new PersistenceConfig(dir, appendOnly, fsync, saveInterval));
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