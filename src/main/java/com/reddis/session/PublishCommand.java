package com.reddis.session;

import com.reddis.command.Command;
import com.reddis.command.CommandArgs;
import com.reddis.protocol.RespValue;

import java.util.List;

/**
 * PUBLISH channel message -> number of clients that received it.
 * A normal command (it needs no session), so it can also be queued inside MULTI.
 */
public final class PublishCommand implements Command {

    private final PubSubBroker broker;

    public PublishCommand(PubSubBroker broker) {
        this.broker = broker;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("publish");
        }
        return new RespValue.RespInteger(broker.publish(args.get(0), args.get(1)));
    }
}