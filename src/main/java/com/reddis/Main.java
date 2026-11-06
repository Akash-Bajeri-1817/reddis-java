package com.reddis;

import com.reddis.command.CommandRouter;
import com.reddis.command.DelCommand;
import com.reddis.command.EchoCommand;
import com.reddis.command.ExistsCommand;
import com.reddis.command.GetCommand;
import com.reddis.command.PingCommand;
import com.reddis.command.SetCommand;
import com.reddis.server.Server;
import com.reddis.storage.DataStore;

import java.io.IOException;

public class Main {

    private static final int DEFAULT_PORT = 6379;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        // One store, shared by every command that needs data.
        DataStore store = new DataStore();

        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));

        new Server(port, router).start();
    }
}