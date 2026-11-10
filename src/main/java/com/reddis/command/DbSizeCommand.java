package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** DBSIZE -> number of keys (like Redis, this can include expired keys that were not purged yet). */
public class DbSizeCommand implements Command {

    private final DataStore store;

    public DbSizeCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (!args.isEmpty()) {
            return CommandArgs.wrongArgs("dbsize");
        }
        return new RespValue.RespInteger(store.size());
    }
}