package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.WrongTypeException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Looks up the handler for a command name and runs it. */
public class CommandRouter {

    private final Map<String, Command> commands = new HashMap<>();

    public void register(String name, Command command) {
        commands.put(name.toUpperCase(Locale.ROOT), command);
    }

    /** @param parts full command including its name at index 0 (never empty) */
    public RespValue route(List<byte[]> parts) {
        String name = new String(parts.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);

        Command command = commands.get(name);
        if (command == null) {
            return new RespValue.RespError("ERR unknown command '" + name + "'");
        }
        try {
            return command.execute(parts.subList(1, parts.size()));
        } catch (WrongTypeException e) {
            // One place translates the storage-layer exception, so EVERY command gets correct behavior.
            return new RespValue.RespError(e.getMessage());
        }
    }
}