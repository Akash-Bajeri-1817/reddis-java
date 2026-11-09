package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.WrongTypeException;

import java.util.ArrayList;
import java.util.List;

/**
 * MSET and MGET.
 *
 * ATOMICITY: these touch several keys one after another. That is only atomic as a group because the
 * event-loop server executes one command at a time (ADR-15). Under the legacy thread-per-client server
 * another thread could slip in between the individual keys.
 */
public final class MultiKeyCommands {

    private final DataStore store;

    private MultiKeyCommands(DataStore store) {
        this.store = store;
    }

    public static void register(CommandRouter router, DataStore store) {
        MultiKeyCommands commands = new MultiKeyCommands(store);
        router.register("MSET", commands::mset);
        router.register("MGET", commands::mget);
    }

    /** MSET key value [key value ...] -> +OK. Like SET, it overwrites any type and clears timeouts. */
    private RespValue mset(List<byte[]> args) {
        if (args.isEmpty() || args.size() % 2 != 0) {
            return CommandArgs.wrongArgs("mset");
        }
        for (int i = 0; i < args.size(); i += 2) {
            store.set(args.get(i), args.get(i + 1));
        }
        return new RespValue.SimpleString("OK");
    }

    /** MGET key [key ...] -> array of values; missing keys and keys of other types give nil (never an error). */
    private RespValue mget(List<byte[]> args) {
        if (args.isEmpty()) {
            return CommandArgs.wrongArgs("mget");
        }
        List<RespValue> values = new ArrayList<>(args.size());
        for (byte[] key : args) {
            byte[] value;
            try {
                value = store.get(key);
            } catch (WrongTypeException e) {
                value = null;
            }
            values.add(value == null ? RespValue.BulkString.NULL : new RespValue.BulkString(value));
        }
        return new RespValue.RespArray(values);
    }
}