package com.reddis.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth about WRITE commands: does it grow memory, can it be logged/replicated verbatim, and
 * which arguments are keys? The AOF/replication propagator, WATCH, the memory guard and the read-only guard all
 * ask this table, so a new write command is declared in exactly one place.
 */
public final class CommandSpecs {

    private enum Keys { NONE, FIRST, FIRST_TWO, ALL, EVERY_OTHER }

    /**
     * @param growsMemory refused with -OOM when memory is full (it can create or enlarge data)
     * @param verbatim    logged/replicated exactly as received; false = needs a custom translation (SET, EXPIRE)
     * @param keys        which arguments are keys the command modifies
     */
    private record Spec(boolean growsMemory, boolean verbatim, Keys keys) { }

    private static final Map<String, Spec> WRITES = new HashMap<>();

    static {
        //     name        grows  verbatim  keys
        write("SET",       true,  false, Keys.FIRST);        // custom: EX/PX become PEXPIREAT; a failed NX/XX is not logged
        write("MSET",      true,  true,  Keys.EVERY_OTHER);
        write("DEL",       false, true,  Keys.ALL);
        write("PERSIST",   false, true,  Keys.FIRST);
        write("EXPIRE",    false, false, Keys.FIRST);        // custom: becomes PEXPIREAT (or DEL)
        write("PEXPIRE",   false, false, Keys.FIRST);
        write("EXPIREAT",  false, true,  Keys.FIRST);
        write("PEXPIREAT", false, true,  Keys.FIRST);
        write("INCR",      true,  true,  Keys.FIRST);
        write("DECR",      true,  true,  Keys.FIRST);
        write("INCRBY",    true,  true,  Keys.FIRST);
        write("DECRBY",    true,  true,  Keys.FIRST);
        write("APPEND",    true,  true,  Keys.FIRST);
        write("RENAME",    false, true,  Keys.FIRST_TWO);
        write("FLUSHALL",  false, true,  Keys.NONE);         // the dispatcher touches every watched key
        write("FLUSHDB",   false, true,  Keys.NONE);
        write("LPUSH",     true,  true,  Keys.FIRST);
        write("RPUSH",     true,  true,  Keys.FIRST);
        write("LPOP",      false, true,  Keys.FIRST);
        write("RPOP",      false, true,  Keys.FIRST);
        write("HSET",      true,  true,  Keys.FIRST);
        write("HDEL",      false, true,  Keys.FIRST);
        write("SADD",      true,  true,  Keys.FIRST);
        write("SREM",      false, true,  Keys.FIRST);
        write("ZADD",      true,  true,  Keys.FIRST);
        write("ZREM",      false, true,  Keys.FIRST);
        write("ZINCRBY",   true,  true,  Keys.FIRST);
    }

    private CommandSpecs() { }

    private static void write(String name, boolean growsMemory, boolean verbatim, Keys keys) {
        WRITES.put(name, new Spec(growsMemory, verbatim, keys));
    }

    /** @param name the command name in upper case */
    public static boolean isWrite(String name) {
        return WRITES.containsKey(name);
    }

    public static boolean growsMemory(String name) {
        Spec spec = WRITES.get(name);
        return spec != null && spec.growsMemory();
    }

    public static boolean propagatesVerbatim(String name) {
        Spec spec = WRITES.get(name);
        return spec != null && spec.verbatim();
    }

    public static Set<String> writeCommandNames() {
        return Collections.unmodifiableSet(WRITES.keySet());
    }

    /** The keys this command modifies, given its arguments (empty for reads and unknown commands). */
    public static List<byte[]> modifiedKeys(String name, List<byte[]> args) {
        Spec spec = WRITES.get(name);
        if (spec == null || args.isEmpty()) {
            return List.of();
        }
        return switch (spec.keys()) {
            case NONE -> List.of();
            case FIRST -> List.of(args.get(0));
            case FIRST_TWO -> args.size() >= 2 ? List.of(args.get(0), args.get(1)) : List.of(args.get(0));
            case ALL -> args;
            case EVERY_OTHER -> {
                List<byte[]> keys = new ArrayList<>();
                for (int i = 0; i < args.size(); i += 2) { // key value key value ...
                    keys.add(args.get(i));
                }
                yield keys;
            }
        };
    }
}