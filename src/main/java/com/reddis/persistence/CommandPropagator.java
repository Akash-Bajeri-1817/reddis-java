package com.reddis.persistence;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides what an executed command contributes to the append-only file.
 *
 *  - reads and FAILED commands: nothing
 *  - deterministic writes: the command itself
 *  - commands that depend on WHEN they run are rewritten with ABSOLUTE deadlines, otherwise replaying the log
 *    later would restart their countdowns:
 *        SET k v EX 10   ->  SET k v  +  PEXPIREAT k <deadline>
 *        EXPIRE k 10     ->  PEXPIREAT k <deadline>
 *        EXPIRE k 0      ->  DEL k   (the key was deleted)
 */
public final class CommandPropagator {

    private static final Set<String> VERBATIM = Set.of(
            "DEL", "MSET", "PERSIST",
            "LPUSH", "RPUSH", "LPOP", "RPOP",
            "HSET", "HDEL", "SADD", "SREM",
            "ZADD", "ZREM", "ZINCRBY",
            "EXPIREAT", "PEXPIREAT");

    private final DataStore store;

    public CommandPropagator(DataStore store) {
        this.store = store;
    }

    /**
     * Call right AFTER the command was executed.
     *
     * @return the commands to append to the log, in order (empty if the command does not change data)
     */
    public List<List<byte[]>> propagate(List<byte[]> command, RespValue reply) {
        if (reply instanceof RespValue.RespError) {
            return List.of();
        }
        String name = new String(command.get(0), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        if (VERBATIM.contains(name)) {
            return List.of(command);
        }
        return switch (name) {
            case "SET" -> propagateSet(command);
            case "EXPIRE", "PEXPIRE" -> propagateExpire(command, reply);
            default -> List.of();
        };
    }

    private List<List<byte[]>> propagateSet(List<byte[]> command) {
        if (command.size() <= 3) {
            return List.of(command); // SET key value: nothing relative about it
        }
        byte[] key = command.get(1);
        List<byte[]> plainSet = List.of(command.get(0), key, command.get(2));
        long deadline = store.expireAtMillis(key); // the exact deadline the command just stored
        if (deadline < 0) {
            return List.of(plainSet);
        }
        return List.of(plainSet, List.of(
                AofFormat.ascii("PEXPIREAT"), key, AofFormat.ascii(Long.toString(deadline))));
    }

    private List<List<byte[]>> propagateExpire(List<byte[]> command, RespValue reply) {
        if (!(reply instanceof RespValue.RespInteger integer) || integer.value() != 1) {
            return List.of(); // the key did not exist: nothing changed
        }
        byte[] key = command.get(1);
        long deadline = store.expireAtMillis(key);
        if (deadline >= 0) {
            return List.of(List.of(
                    AofFormat.ascii("PEXPIREAT"), key, AofFormat.ascii(Long.toString(deadline))));
        }
        if (deadline == DataStore.TTL_MISSING) { // a non-positive TTL deleted the key
            return List.of(List.of(AofFormat.ascii("DEL"), key));
        }
        return List.of();
    }
}