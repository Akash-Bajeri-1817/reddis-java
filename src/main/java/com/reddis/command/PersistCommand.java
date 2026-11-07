package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** PERSIST key -> 1 if a timeout was removed, 0 if the key is missing or had no timeout. */
public class PersistCommand implements Command {

    private final DataStore store;

    public PersistCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("persist");
        }
        return new RespValue.RespInteger(store.persist(args.get(0)) ? 1 : 0);
    }
}