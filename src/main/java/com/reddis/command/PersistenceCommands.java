package com.reddis.command;

import com.reddis.persistence.Persistence;
import com.reddis.protocol.RespValue;

import java.io.IOException;
import java.util.List;

/** SAVE, BGSAVE, LASTSAVE and REWRITEAOF. */
public final class PersistenceCommands {

    private PersistenceCommands() { }

    public static void register(CommandRouter router, Persistence persistence) {
        router.register("SAVE", args -> save(persistence, args));
        router.register("BGSAVE", args -> bgsave(persistence, args));
        router.register("LASTSAVE", args -> lastsave(persistence, args));
        router.register("REWRITEAOF", args -> rewriteAof(persistence, args));
    }

    private static RespValue save(Persistence persistence, List<byte[]> args) {
        if (!args.isEmpty()) {
            return CommandArgs.wrongArgs("save");
        }
        try {
            persistence.save();
            return new RespValue.SimpleString("OK");
        } catch (IOException | IllegalStateException e) {
            return error(e);
        }
    }

    private static RespValue bgsave(Persistence persistence, List<byte[]> args) {
        if (!args.isEmpty()) {
            return CommandArgs.wrongArgs("bgsave");
        }
        try {
            return persistence.startBackgroundSave()
                    ? new RespValue.SimpleString("Background saving started")
                    : new RespValue.RespError("ERR Background save already in progress");
        } catch (IOException e) {
            return error(e);
        }
    }

    private static RespValue lastsave(Persistence persistence, List<byte[]> args) {
        if (!args.isEmpty()) {
            return CommandArgs.wrongArgs("lastsave");
        }
        return new RespValue.RespInteger(persistence.lastSaveSeconds());
    }

    /** Not a real Redis command: real Redis has BGREWRITEAOF, which forks. Ours blocks the loop while it runs. */
    private static RespValue rewriteAof(Persistence persistence, List<byte[]> args) {
        if (!args.isEmpty()) {
            return CommandArgs.wrongArgs("rewriteaof");
        }
        try {
            persistence.rewriteAof();
            return new RespValue.SimpleString("OK");
        } catch (IOException | IllegalStateException e) {
            return error(e);
        }
    }

    private static RespValue error(Exception e) {
        return new RespValue.RespError("ERR " + e.getMessage());
    }
}