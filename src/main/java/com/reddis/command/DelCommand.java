package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** DEL key [key ...] -> integer: how many of the keys existed and were removed. */
public class DelCommand implements Command {

    private final DataStore store;

    public DelCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.isEmpty()) {
            return new RespValue.RespError("ERR wrong number of arguments for 'del' command");
        }
        long removed = 0;
        for (byte[] key : args) {
            if (store.delete(key)) {
                removed++;
            }
        }
        return new RespValue.RespInteger(removed);
    }
}