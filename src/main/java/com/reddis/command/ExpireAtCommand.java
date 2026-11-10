package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;
import java.util.OptionalLong;

/**
 * EXPIREAT key unix-seconds  /  PEXPIREAT key unix-milliseconds -> 1 if the key exists, 0 if not.
 * Absolute deadlines: the form the AOF uses so a replay never restarts a countdown.
 */
public class ExpireAtCommand implements Command {

    private final String name;      // "expireat" or "pexpireat"
    private final DataStore store;
    private final long unitMillis;  // 1000 for seconds, 1 for milliseconds

    public ExpireAtCommand(String name, DataStore store, long unitMillis) {
        this.name = name;
        this.store = store;
        this.unitMillis = unitMillis;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs(name);
        }
        OptionalLong timestamp = CommandArgs.parseLong(args.get(1));
        if (timestamp.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }
        long deadlineMillis;
        try {
            deadlineMillis = Math.multiplyExact(timestamp.getAsLong(), unitMillis);
        } catch (ArithmeticException e) {
            return CommandArgs.invalidExpire(name);
        }
        return new RespValue.RespInteger(store.expireAt(args.get(0), deadlineMillis) ? 1 : 0);
    }
}