package com.reddis.replication;

import com.reddis.command.CommandGuard;
import com.reddis.command.WriteCommands;
import com.reddis.protocol.RespValue;

/** A replica refuses writes from clients (but accepts the master's stream, which is flagged as "applying"). */
public final class ReadOnlyGuard implements CommandGuard {

    private static final RespValue READONLY =
            new RespValue.RespError("READONLY You can't write against a read only replica.");

    private final ReplicaState state;

    public ReadOnlyGuard(ReplicaState state) {
        this.state = state;
    }

    @Override
    public RespValue beforeExecute(String commandName) {
        if (state.isReplica() && !state.isApplying() && WriteCommands.isWrite(commandName)) {
            return READONLY;
        }
        return null;
    }
}