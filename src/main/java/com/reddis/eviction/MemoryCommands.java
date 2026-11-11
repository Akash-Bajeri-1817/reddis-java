package com.reddis.eviction;

import com.reddis.command.CommandArgs;
import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.KeySample;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** CONFIG GET/SET (memory settings only), INFO, MEMORY USAGE, OBJECT IDLETIME / FREQ. */
public final class MemoryCommands {

    private MemoryCommands() { }

    public static void register(CommandRouter router, MemoryGuard guard, DataStore store) {
        router.register("CONFIG", args -> config(guard, args));
        router.register("INFO", args -> info(guard, store));
        router.register("MEMORY", args -> memory(store, args));
        router.register("OBJECT", args -> object(guard, store, args));
    }

    // ------------------------------------------------------------------ CONFIG

    private static RespValue config(MemoryGuard guard, List<byte[]> args) {
        if (args.isEmpty()) {
            return CommandArgs.wrongArgs("config");
        }
        String subcommand = CommandArgs.keyword(args.get(0));
        return switch (subcommand) {
            case "GET" -> configGet(guard, args);
            case "SET" -> configSet(guard, args);
            default -> new RespValue.RespError("ERR unknown subcommand '" + subcommand + "' for 'config'");
        };
    }

    private static RespValue configGet(MemoryGuard guard, List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("config|get");
        }
        String name = lower(args.get(1));
        String value = switch (name) {
            case "maxmemory" -> Long.toString(guard.maxMemory());
            case "maxmemory-policy" -> guard.policy().redisName();
            case "maxmemory-samples" -> Integer.toString(guard.samples());
            default -> null;
        };
        if (value == null) {
            return new RespValue.RespArray(List.of()); // unknown parameter: nothing to report
        }
        return new RespValue.RespArray(List.of(CommandArgs.bulk(name), CommandArgs.bulk(value)));
    }

    private static RespValue configSet(MemoryGuard guard, List<byte[]> args) {
        if (args.size() != 3) {
            return CommandArgs.wrongArgs("config|set");
        }
        String name = lower(args.get(1));
        String value = new String(args.get(2), StandardCharsets.UTF_8);
        try {
            switch (name) {
                case "maxmemory" -> guard.setMaxMemory(MemoryConfig.parseBytes(value));
                case "maxmemory-policy" -> guard.setPolicy(EvictionPolicy.parse(value));
                case "maxmemory-samples" -> guard.setSamples(Integer.parseInt(value));
                default -> {
                    return new RespValue.RespError("ERR Unknown option or number of arguments for CONFIG SET - '" + name + "'");
                }
            }
        } catch (IllegalArgumentException e) { // includes NumberFormatException
            return new RespValue.RespError("ERR Invalid argument '" + value + "' for CONFIG SET '" + name + "'");
        }
        return new RespValue.SimpleString("OK");
    }

    // -------------------------------------------------------------------- INFO

    private static RespValue info(MemoryGuard guard, DataStore store) {
        String text = "# Memory\r\n"
                + "used_memory:" + store.usedMemory() + "\r\n"
                + "used_memory_human:" + human(store.usedMemory()) + "\r\n"
                + "maxmemory:" + guard.maxMemory() + "\r\n"
                + "maxmemory_policy:" + guard.policy().redisName() + "\r\n"
                + "maxmemory_samples:" + guard.samples() + "\r\n"
                + "# Stats\r\n"
                + "evicted_keys:" + guard.evictedKeys() + "\r\n"
                + "# Keyspace\r\n"
                + "keys:" + store.size() + "\r\n";
        return CommandArgs.bulk(text);
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.2fK", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2fM", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2fG", bytes / (1024.0 * 1024 * 1024));
    }

    // ------------------------------------------------------------------ MEMORY

    private static RespValue memory(DataStore store, List<byte[]> args) {
        if (args.size() != 2 || !CommandArgs.keyword(args.get(0)).equals("USAGE")) {
            return new RespValue.RespError("ERR syntax: MEMORY USAGE key");
        }
        long bytes = store.memoryUsage(args.get(1));
        return bytes < 0 ? RespValue.BulkString.NULL : new RespValue.RespInteger(bytes);
    }

    // ------------------------------------------------------------------ OBJECT

    private static RespValue object(MemoryGuard guard, DataStore store, List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("object");
        }
        String subcommand = CommandArgs.keyword(args.get(0));
        if (!subcommand.equals("IDLETIME") && !subcommand.equals("FREQ")) {
            return new RespValue.RespError("ERR unknown subcommand '" + subcommand + "' for 'object'");
        }
        KeySample sample = store.inspect(args.get(1)); // inspecting does not count as an access
        if (sample == null) {
            return RespValue.BulkString.NULL;
        }
        if (subcommand.equals("IDLETIME")) {
            return new RespValue.RespInteger((guard.clock().millis() - sample.lastAccessMillis()) / 1000);
        }
        if (!guard.policy().usesFrequency()) {
            return new RespValue.RespError(
                    "ERR An LFU maxmemory policy is not selected, access frequency not tracked.");
        }
        return new RespValue.RespInteger(sample.frequency());
    }

    private static String lower(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
    }
}