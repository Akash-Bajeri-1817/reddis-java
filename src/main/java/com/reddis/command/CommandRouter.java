package com.reddis.command;

import com.reddis.protocol.RespValue;

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
        // Locale.ROOT avoids locale bugs (e.g. in a Turkish locale "i".toUpperCase() gives "İ").
        String name = new String(parts.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);

        Command command = commands.get(name);
        if (command == null) {
            return new RespValue.RespError("ERR unknown command '" + name + "'");
        }
        return command.execute(parts.subList(1, parts.size()));
    }
}