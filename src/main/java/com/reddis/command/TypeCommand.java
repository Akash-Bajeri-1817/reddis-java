package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** TYPE key -> +string, +list, +hash, +set, or +none. */
public class TypeCommand implements Command {

    private final DataStore store;

    public TypeCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("type");
        }
        return new RespValue.SimpleString(store.type(args.get(0)));
    }
}