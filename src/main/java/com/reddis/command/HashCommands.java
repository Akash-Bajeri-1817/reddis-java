package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.HashValue;

import java.util.List;

/** HSET, HGET, HDEL, HGETALL, HLEN, HEXISTS. */
public final class HashCommands {

    private final DataStore store;

    private HashCommands(DataStore store) {
        this.store = store;
    }

    public static void register(CommandRouter router, DataStore store) {
        HashCommands commands = new HashCommands(store);
        router.register("HSET", commands::hset);
        router.register("HGET", commands::hget);
        router.register("HDEL", commands::hdel);
        router.register("HGETALL", commands::hgetall);
        router.register("HLEN", commands::hlen);
        router.register("HEXISTS", commands::hexists);
    }

    /** HSET key field value [field value ...] -> number of NEW fields (overwrites are not counted). */
    private RespValue hset(List<byte[]> args) {
        // key + pairs: the argument count must be odd and at least 3
        if (args.size() < 3 || args.size() % 2 == 0) {
            return CommandArgs.wrongArgs("hset");
        }
        int added = store.access(args.get(0), HashValue.class, HashValue::new, hash -> {
            int newFields = 0;
            for (int i = 1; i < args.size(); i += 2) {
                if (hash.put(args.get(i), args.get(i + 1))) {
                    newFields++;
                }
            }
            return newFields;
        });
        return new RespValue.RespInteger(added);
    }

    private RespValue hget(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("hget");
        }
        byte[] field = args.get(1);
        byte[] value = store.access(args.get(0), HashValue.class, HashValue::new, hash -> hash.get(field));
        return CommandArgs.bulkOrNull(value);
    }

    private RespValue hdel(List<byte[]> args) {
        if (args.size() < 2) {
            return CommandArgs.wrongArgs("hdel");
        }
        List<byte[]> fields = args.subList(1, args.size());
        int removed = store.access(args.get(0), HashValue.class, HashValue::new, hash -> {
            int count = 0;
            for (byte[] field : fields) {
                if (hash.remove(field)) {
                    count++;
                }
            }
            return count;
        });
        return new RespValue.RespInteger(removed);
    }

    private RespValue hgetall(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("hgetall");
        }
        List<byte[]> flat = store.access(args.get(0), HashValue.class, HashValue::new, HashValue::flatten);
        return CommandArgs.bulkArray(flat);
    }

    private RespValue hlen(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("hlen");
        }
        int length = store.access(args.get(0), HashValue.class, HashValue::new, HashValue::size);
        return new RespValue.RespInteger(length);
    }

    private RespValue hexists(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("hexists");
        }
        byte[] field = args.get(1);
        boolean found = store.access(args.get(0), HashValue.class, HashValue::new, hash -> hash.contains(field));
        return new RespValue.RespInteger(found ? 1 : 0);
    }
}