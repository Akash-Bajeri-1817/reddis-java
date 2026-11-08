package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.ListValue;

import java.util.List;
import java.util.OptionalLong;

/** LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE, LINDEX. One class registers all list commands as lambdas. */
public final class ListCommands {

    private final DataStore store;

    private ListCommands(DataStore store) {
        this.store = store;
    }

    public static void register(CommandRouter router, DataStore store) {
        ListCommands commands = new ListCommands(store);
        router.register("LPUSH", args -> commands.push("lpush", args, true));
        router.register("RPUSH", args -> commands.push("rpush", args, false));
        router.register("LPOP", args -> commands.pop("lpop", args, true));
        router.register("RPOP", args -> commands.pop("rpop", args, false));
        router.register("LLEN", commands::llen);
        router.register("LRANGE", commands::lrange);
        router.register("LINDEX", commands::lindex);
    }

    /** LPUSH pushes each value to the front IN ORDER, so "LPUSH k a b c" yields c, b, a. */
    private RespValue push(String name, List<byte[]> args, boolean left) {
        if (args.size() < 2) {
            return CommandArgs.wrongArgs(name);
        }
        List<byte[]> values = args.subList(1, args.size());
        int length = store.access(args.get(0), ListValue.class, ListValue::new, list -> {
            for (byte[] value : values) {
                if (left) {
                    list.pushLeft(value);
                } else {
                    list.pushRight(value);
                }
            }
            return list.size();
        });
        return new RespValue.RespInteger(length);
    }

    private RespValue pop(String name, List<byte[]> args, boolean left) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs(name);
        }
        byte[] value = store.access(args.get(0), ListValue.class, ListValue::new,
                list -> left ? list.popLeft() : list.popRight());
        return CommandArgs.bulkOrNull(value);
    }

    private RespValue llen(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("llen");
        }
        int length = store.access(args.get(0), ListValue.class, ListValue::new, ListValue::size);
        return new RespValue.RespInteger(length);
    }

    private RespValue lrange(List<byte[]> args) {
        if (args.size() != 3) {
            return CommandArgs.wrongArgs("lrange");
        }
        OptionalLong start = CommandArgs.parseLong(args.get(1));
        OptionalLong stop = CommandArgs.parseLong(args.get(2));
        if (start.isEmpty() || stop.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }
        long from = start.getAsLong();
        long to = stop.getAsLong();
        List<byte[]> items = store.access(args.get(0), ListValue.class, ListValue::new,
                list -> list.range(from, to));
        return CommandArgs.bulkArray(items);
    }

    private RespValue lindex(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("lindex");
        }
        OptionalLong index = CommandArgs.parseLong(args.get(1));
        if (index.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }
        long position = index.getAsLong();
        byte[] value = store.access(args.get(0), ListValue.class, ListValue::new, list -> list.get(position));
        return CommandArgs.bulkOrNull(value);
    }
}