package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.util.List;

/** One Redis command (PING, ECHO, later SET, GET, ...). This is the Command pattern. */
public interface Command {

    /**
     * @param args the arguments AFTER the command name, as raw bytes
     * @return the reply to send to the client
     */
    RespValue execute(List<byte[]> args);
}