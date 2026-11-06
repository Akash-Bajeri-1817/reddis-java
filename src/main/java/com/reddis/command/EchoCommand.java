package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.util.List;

/** ECHO message -> the same message back as a bulk string. */
public class EchoCommand implements Command {

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 1) {
            return new RespValue.RespError("ERR wrong number of arguments for 'echo' command");
        }
        return new RespValue.BulkString(args.get(0));
    }
}