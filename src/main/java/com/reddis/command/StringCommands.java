package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** APPEND and STRLEN. */
public final class StringCommands {

    private StringCommands() { }

    public static void register(CommandRouter router, DataStore store) {
        router.register("APPEND", args -> append(store, args));
        router.register("STRLEN", args -> strlen(store, args));
    }

    private static RespValue append(DataStore store, List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("append");
        }
        try {
            return new RespValue.RespInteger(store.append(args.get(0), args.get(1)));
        } catch (IllegalArgumentException e) {
            return new RespValue.RespError("ERR string exceeds maximum allowed size (512MB)");
        }
    }

    private static RespValue strlen(DataStore store, List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("strlen");
        }
        byte[] value = store.get(args.get(0));
        return new RespValue.RespInteger(value == null ? 0 : value.length);
    }
}