package com.reddis.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Which keys does a write command modify? Used by WATCH to detect conflicting writes. */
public final class WriteCommands {

    private static final Set<String> SINGLE_KEY = Set.of(
            "SET", "PERSIST", "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT",
            "LPUSH", "RPUSH", "LPOP", "RPOP",
            "HSET", "HDEL", "SADD", "SREM",
            "ZADD", "ZREM", "ZINCRBY");

    private WriteCommands() { }

    /**
     * @param upperCaseName the command name in upper case
     * @param args          the arguments after the command name
     * @return the keys this command may modify (empty for reads and other commands)
     */
    public static List<byte[]> modifiedKeys(String upperCaseName, List<byte[]> args) {
        if (args.isEmpty()) {
            return List.of();
        }
        if (SINGLE_KEY.contains(upperCaseName)) {
            return List.of(args.get(0));
        }
        if (upperCaseName.equals("DEL")) {
            return args; // every argument is a key
        }
        if (upperCaseName.equals("MSET")) {
            List<byte[]> keys = new ArrayList<>();
            for (int i = 0; i < args.size(); i += 2) { // key value key value ...
                keys.add(args.get(i));
            }
            return keys;
        }
        return List.of();
    }
}