package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;

/** GET key -> the value as a bulk string, or a null bulk string if the key is absent. */
public class GetCommand implements Command {

    private final DataStore store;

    public GetCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 1) {
            return new RespValue.RespError("ERR wrong number of arguments for 'get' command");
        }
        byte[] value = store.get(args.get(0));
        return value == null ? RespValue.BulkString.NULL : new RespValue.BulkString(value);
    }
}