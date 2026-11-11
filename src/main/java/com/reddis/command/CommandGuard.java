package com.reddis.command;

import com.reddis.protocol.RespValue;

/** A check run by the router before every command executes (used for memory limits). */
@FunctionalInterface
public interface CommandGuard {

    /**
     * @param commandName the command name in upper case
     * @return null to let the command run, or the reply to send INSTEAD of running it
     */
    RespValue beforeExecute(String commandName);
}