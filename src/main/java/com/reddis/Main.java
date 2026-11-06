package com.reddis;

import com.reddis.command.CommandRouter;
import com.reddis.command.EchoCommand;
import com.reddis.command.PingCommand;
import com.reddis.server.Server;

import java.io.IOException;

public class Main {

    private static final int DEFAULT_PORT = 6379;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());

        new Server(port, router).start();
    }
}