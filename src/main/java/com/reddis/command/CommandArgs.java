package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalLong;

/** Small helpers shared by commands that take numeric or keyword arguments. */
final class CommandArgs {

    static final RespValue NOT_AN_INTEGER = new RespValue.RespError("ERR value is not an integer or out of range");
    static final RespValue SYNTAX_ERROR = new RespValue.RespError("ERR syntax error");

    private CommandArgs() { }

    /** Parses a decimal integer argument; empty if it is not one (or does not fit in a long). */
    static OptionalLong parseLong(byte[] arg) {
        try {
            return OptionalLong.of(Long.parseLong(new String(arg, StandardCharsets.US_ASCII)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** Keyword arguments such as EX/PX are case-insensitive. */
    static String keyword(byte[] arg) {
        return new String(arg, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }

    static RespValue wrongArgs(String commandName) {
        return new RespValue.RespError("ERR wrong number of arguments for '" + commandName + "' command");
    }

    static RespValue invalidExpire(String commandName) {
        return new RespValue.RespError("ERR invalid expire time in '" + commandName + "' command");
    }
}