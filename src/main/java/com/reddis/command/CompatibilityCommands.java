package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/** HELLO, CLIENT, COMMAND, SELECT and TIME: what real client libraries send when they connect or poll. */
public final class CompatibilityCommands {

    private static final RespValue OK = new RespValue.SimpleString("OK");

    private CompatibilityCommands() { }

    public static void register(CommandRouter router) {
        router.register("HELLO", CompatibilityCommands::hello);
        router.register("CLIENT", CompatibilityCommands::client);
        router.register("COMMAND", args -> new RespValue.RespArray(List.of())); // we do not publish command metadata
        router.register("SELECT", CompatibilityCommands::select);
        router.register("TIME", args -> time());
    }

    /** HELLO [protover]: only RESP2 is spoken, so HELLO 3 is refused and clients fall back to RESP2. */
    private static RespValue hello(List<byte[]> args) {
        if (!args.isEmpty()) {
            OptionalLong version = CommandArgs.parseLong(args.get(0));
            if (version.isEmpty()) {
                return new RespValue.RespError("ERR Protocol version is not an integer or out of range");
            }
            if (version.getAsLong() != 2) {
                return new RespValue.RespError("NOPROTO unsupported protocol version");
            }
        }
        return new RespValue.RespArray(List.of(
                CommandArgs.bulk("server"), CommandArgs.bulk("reddis"),
                CommandArgs.bulk("version"), CommandArgs.bulk("1.0.0"),
                CommandArgs.bulk("proto"), new RespValue.RespInteger(2),
                CommandArgs.bulk("id"), new RespValue.RespInteger(1),
                CommandArgs.bulk("mode"), CommandArgs.bulk("standalone"),
                CommandArgs.bulk("role"), CommandArgs.bulk("master"),
                CommandArgs.bulk("modules"), new RespValue.RespArray(List.of())));
    }

    /** CLIENT SETNAME / SETINFO are accepted and ignored; GETNAME says "no name". */
    private static RespValue client(List<byte[]> args) {
        if (args.isEmpty()) {
            return CommandArgs.wrongArgs("client");
        }
        String subcommand = CommandArgs.keyword(args.get(0));
        return switch (subcommand) {
            case "SETNAME", "SETINFO" -> OK;
            case "GETNAME" -> RespValue.BulkString.NULL;
            default -> new RespValue.RespError(
                    "ERR unknown subcommand '" + subcommand.toLowerCase(Locale.ROOT) + "' for 'client'");
        };
    }

    /** SELECT 0 only: there is a single database. */
    private static RespValue select(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("select");
        }
        OptionalLong index = CommandArgs.parseLong(args.get(0));
        if (index.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }
        return index.getAsLong() == 0 ? OK : new RespValue.RespError("ERR DB index is out of range");
    }

    /** TIME -> [unix seconds, microseconds]. */
    private static RespValue time() {
        Instant now = Instant.now();
        return new RespValue.RespArray(List.of(
                CommandArgs.bulk(Long.toString(now.getEpochSecond())),
                CommandArgs.bulk(Long.toString(now.getNano() / 1_000))));
    }
}