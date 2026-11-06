package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** EXISTS key [key ...] -> integer: how many of the given keys exist (duplicates count twice). */
public class ExistsCommand implements Command {

    private final DataStore store;

    public ExistsCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.isEmpty()) {
            return new RespValue.RespError("ERR wrong number of arguments for 'exists' command");
        }
        long count = 0;
        for (byte[] key : args) {
            if (store.exists(key)) {
                count++;
            }
        }
        return new RespValue.RespInteger(count);
    }
}