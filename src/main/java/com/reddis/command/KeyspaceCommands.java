package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.util.GlobMatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** KEYS, RENAME, FLUSHALL and FLUSHDB (there is a single database, so they do the same). */
public final class KeyspaceCommands {

    private KeyspaceCommands() { }

    public static void register(CommandRouter router, DataStore store) {
        router.register("KEYS", args -> keys(store, args));
        router.register("RENAME", args -> rename(store, args));
        router.register("FLUSHALL", args -> flush("flushall", store, args));
        router.register("FLUSHDB", args -> flush("flushdb", store, args));
    }

    /** KEYS pattern: O(n) over the whole dataset and it blocks the event loop meanwhile (avoid it in production). */
    private static RespValue keys(DataStore store, List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("keys");
        }
        byte[] pattern = args.get(0);
        List<byte[]> matches = new ArrayList<>();
        try {
            store.forEachEntry((key, value, expireAt) -> {
                if (GlobMatcher.matches(pattern, key)) {
                    matches.add(key);
                }
            });
        } catch (IOException e) {
            return new RespValue.RespError("ERR " + e.getMessage()); // cannot happen: the visitor does no I/O
        }
        return CommandArgs.bulkArray(matches);
    }

    private static RespValue rename(DataStore store, List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("rename");
        }
        return store.rename(args.get(0), args.get(1))
                ? new RespValue.SimpleString("OK")
                : new RespValue.RespError("ERR no such key");
    }

    /** FLUSHALL [ASYNC|SYNC]: deletes every key. The option is accepted and ignored. */
    private static RespValue flush(String name, DataStore store, List<byte[]> args) {
        if (args.size() > 1) {
            return CommandArgs.wrongArgs(name);
        }
        if (args.size() == 1) {
            String option = CommandArgs.keyword(args.get(0));
            if (!option.equals("ASYNC") && !option.equals("SYNC")) {
                return CommandArgs.SYNTAX_ERROR;
            }
        }
        store.clear();
        return new RespValue.SimpleString("OK");
    }
}