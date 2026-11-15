package com.reddis.command;

import java.util.List;

/** Kept for existing callers: asks the single table in CommandSpecs. */
public final class WriteCommands {

    private WriteCommands() { }

    public static boolean isWrite(String upperCaseName) {
        return CommandSpecs.isWrite(upperCaseName);
    }

    public static List<byte[]> modifiedKeys(String upperCaseName, List<byte[]> args) {
        return CommandSpecs.modifiedKeys(upperCaseName, args);
    }
}