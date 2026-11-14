package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.util.List;

/** Runs several guards in order; the first one that refuses a command decides the reply. */
public final class CompositeGuard implements CommandGuard {

    private final List<CommandGuard> guards;

    public CompositeGuard(CommandGuard... guards) {
        this.guards = List.of(guards);
    }

    @Override
    public RespValue beforeExecute(String commandName) {
        for (CommandGuard guard : guards) {
            RespValue denied = guard.beforeExecute(commandName);
            if (denied != null) {
                return denied;
            }
        }
        return null;
    }
}