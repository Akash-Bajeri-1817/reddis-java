package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** SET key value -> +OK. Options (EX, NX, ...) arrive in later modules. */
public class SetCommand implements Command {

    private final DataStore store;

    public SetCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() < 2) {
            return new RespValue.RespError("ERR wrong number of arguments for 'set' command");
        }
        if (args.size() > 2) {
            return new RespValue.RespError("ERR syntax error");
        }
        store.set(args.get(0), args.get(1));
        return new RespValue.SimpleString("OK");
    }
}