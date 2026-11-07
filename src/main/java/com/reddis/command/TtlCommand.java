package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/**
 * TTL key / PTTL key -> remaining lifetime, -1 if the key has no timeout, -2 if it does not exist.
 * TTL rounds to the nearest second, like Redis.
 */
public class TtlCommand implements Command {

    private final String name;       // "ttl" or "pttl"
    private final DataStore store;
    private final boolean inMillis;

    public TtlCommand(String name, DataStore store, boolean inMillis) {
        this.name = name;
        this.store = store;
        this.inMillis = inMillis;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs(name);
        }
        long millis = store.ttlMillis(args.get(0));
        if (millis < 0 || inMillis) {
            return new RespValue.RespInteger(millis); // -1 / -2 pass through unchanged
        }
        long seconds = millis / 1000 + (millis % 1000 >= 500 ? 1 : 0); // round to nearest, no overflow
        return new RespValue.RespInteger(seconds);
    }
}