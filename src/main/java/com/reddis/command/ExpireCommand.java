package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;
import java.util.OptionalLong;

/**
 * EXPIRE key seconds  /  PEXPIRE key milliseconds -> 1 if the key exists, 0 if not.
 * One class serves both commands; they differ only in the time unit.
 */
public class ExpireCommand implements Command {

    private final String name;      // "expire" or "pexpire" (used in error messages)
    private final DataStore store;
    private final long unitMillis;  // 1000 for seconds, 1 for milliseconds

    public ExpireCommand(String name, DataStore store, long unitMillis) {
        this.name = name;
        this.store = store;
        this.unitMillis = unitMillis;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs(name);
        }
        OptionalLong amount = CommandArgs.parseLong(args.get(1));
        if (amount.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }
        long ttlMillis;
        try {
            ttlMillis = Math.multiplyExact(amount.getAsLong(), unitMillis);
        } catch (ArithmeticException e) {
            return CommandArgs.invalidExpire(name);
        }
        return new RespValue.RespInteger(store.expire(args.get(0), ttlMillis) ? 1 : 0);
    }
}