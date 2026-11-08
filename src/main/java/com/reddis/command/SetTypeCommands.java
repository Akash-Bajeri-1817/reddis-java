package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.SetValue;

import java.util.List;

/** SADD, SREM, SMEMBERS, SISMEMBER, SCARD: the commands of the Redis SET data type. */
public final class SetTypeCommands {

    private final DataStore store;

    private SetTypeCommands(DataStore store) {
        this.store = store;
    }

    public static void register(CommandRouter router, DataStore store) {
        SetTypeCommands commands = new SetTypeCommands(store);
        router.register("SADD", commands::sadd);
        router.register("SREM", commands::srem);
        router.register("SMEMBERS", commands::smembers);
        router.register("SISMEMBER", commands::sismember);
        router.register("SCARD", commands::scard);
    }

    /** SADD key member [member ...] -> number of members that were NEW. */
    private RespValue sadd(List<byte[]> args) {
        if (args.size() < 2) {
            return CommandArgs.wrongArgs("sadd");
        }
        List<byte[]> members = args.subList(1, args.size());
        int added = store.access(args.get(0), SetValue.class, SetValue::new, set -> {
            int count = 0;
            for (byte[] member : members) {
                if (set.add(member)) {
                    count++;
                }
            }
            return count;
        });
        return new RespValue.RespInteger(added);
    }

    private RespValue srem(List<byte[]> args) {
        if (args.size() < 2) {
            return CommandArgs.wrongArgs("srem");
        }
        List<byte[]> members = args.subList(1, args.size());
        int removed = store.access(args.get(0), SetValue.class, SetValue::new, set -> {
            int count = 0;
            for (byte[] member : members) {
                if (set.remove(member)) {
                    count++;
                }
            }
            return count;
        });
        return new RespValue.RespInteger(removed);
    }

    private RespValue smembers(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("smembers");
        }
        List<byte[]> members = store.access(args.get(0), SetValue.class, SetValue::new, SetValue::members);
        return CommandArgs.bulkArray(members);
    }

    private RespValue sismember(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("sismember");
        }
        byte[] member = args.get(1);
        boolean found = store.access(args.get(0), SetValue.class, SetValue::new, set -> set.contains(member));
        return new RespValue.RespInteger(found ? 1 : 0);
    }

    private RespValue scard(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("scard");
        }
        int size = store.access(args.get(0), SetValue.class, SetValue::new, SetValue::size);
        return new RespValue.RespInteger(size);
    }
}