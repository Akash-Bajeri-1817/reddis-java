package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.util.List;

/** PING -> +PONG, PING msg -> $len msg */
public class PingCommand implements Command {

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.isEmpty()) {
            return new RespValue.SimpleString("PONG");
        }
        if (args.size() == 1) {
            return new RespValue.BulkString(args.get(0));
        }
        return new RespValue.RespError("ERR wrong number of arguments for 'ping' command");
    }
}