package com.reddis.replication;

import com.reddis.command.Command;
import com.reddis.command.CommandArgs;
import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

/** REPLICAOF / SLAVEOF, ROLE, and the replication section of INFO. */
public final class ReplicationCommands {

    private static final RespValue OK = new RespValue.SimpleString("OK");

    private ReplicationCommands() { }

    /** Register AFTER the commands that define INFO: this decorates the existing INFO instead of replacing it. */
    public static void register(CommandRouter router, Replication replication) {
        Command previousInfo = router.lookup("INFO");
        router.register("INFO", args -> info(previousInfo, replication, args));
        router.register("REPLICAOF", args -> replicaOf("replicaof", replication, args));
        router.register("SLAVEOF", args -> replicaOf("slaveof", replication, args));
        router.register("ROLE", args -> role(replication));
    }

    private static RespValue replicaOf(String name, Replication replication, List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs(name);
        }
        String host = new String(args.get(0), StandardCharsets.UTF_8);
        if (host.equalsIgnoreCase("NO") && CommandArgs.keyword(args.get(1)).equals("ONE")) {
            replication.promote();
            return OK;
        }
        OptionalLong port = CommandArgs.parseLong(args.get(1));
        if (port.isEmpty() || port.getAsLong() < 1 || port.getAsLong() > 65_535) {
            return new RespValue.RespError("ERR Invalid master port");
        }
        try {
            replication.replicaOf(host, (int) port.getAsLong());
        } catch (IllegalStateException e) {
            return new RespValue.RespError("ERR " + e.getMessage());
        }
        return OK;
    }

    /** "INFO replication" gives only that section; plain "INFO" gives the previous INFO text plus this section. */
    private static RespValue info(Command previousInfo, Replication replication, List<byte[]> args) {
        boolean onlyReplication = args.size() == 1 && CommandArgs.keyword(args.get(0)).equals("REPLICATION");
        if (onlyReplication || previousInfo == null) {
            return CommandArgs.bulk(replication.infoText());
        }
        RespValue base = previousInfo.execute(args);
        if (base instanceof RespValue.BulkString bulk && !bulk.isNull()) {
            return CommandArgs.bulk(new String(bulk.data(), StandardCharsets.UTF_8) + replication.infoText());
        }
        return base;
    }

    private static RespValue role(Replication replication) {
        if (replication.isReplica()) {
            return new RespValue.RespArray(List.of(
                    CommandArgs.bulk("slave"),
                    CommandArgs.bulk(replication.masterHost()),
                    new RespValue.RespInteger(replication.masterPort()),
                    CommandArgs.bulk(replication.isLinkUp() ? "connected" : "connect"),
                    new RespValue.RespInteger(replication.appliedOffset())));
        }
        return new RespValue.RespArray(List.of(
                CommandArgs.bulk("master"),
                new RespValue.RespInteger(replication.masterOffset())));
    }
}