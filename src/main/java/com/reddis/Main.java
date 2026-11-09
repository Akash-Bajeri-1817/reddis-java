package com.reddis;

import com.reddis.command.CommandRouter;
import com.reddis.command.DelCommand;
import com.reddis.command.EchoCommand;
import com.reddis.command.ExistsCommand;
import com.reddis.command.ExpireCommand;
import com.reddis.command.GetCommand;
import com.reddis.command.HashCommands;
import com.reddis.command.ListCommands;
import com.reddis.command.MultiKeyCommands;
import com.reddis.command.PersistCommand;
import com.reddis.command.PingCommand;
import com.reddis.command.SetCommand;
import com.reddis.command.SetTypeCommands;
import com.reddis.command.SortedSetCommands;
import com.reddis.command.TtlCommand;
import com.reddis.command.TypeCommand;
import com.reddis.server.EventLoopServer;
import com.reddis.server.Server;
import com.reddis.storage.DataStore;
import com.reddis.storage.ExpiryPurger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Usage: Main [port] [--blocking]
 * Default: the single-threaded event-loop server (ADR-15). --blocking starts the legacy
 * thread-per-client server, kept so both models can be benchmarked against each other.
 */
public class Main {

    private static final int DEFAULT_PORT = 6379;
    private static final long PURGE_INTERVAL_MILLIS = 100; // Redis's default is also ~10 runs per second

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        boolean blocking = false;
        for (String arg : args) {
            if (arg.equals("--blocking")) {
                blocking = true;
            } else {
                port = Integer.parseInt(arg);
            }
        }

        DataStore store = new DataStore();
        CommandRouter router = buildRouter(store);

        if (blocking) {
            runBlocking(port, router, store);
        } else {
            runEventLoop(port, router, store);
        }
    }

    private static CommandRouter buildRouter(DataStore store) {
        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());

        // strings and keys
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        router.register("TYPE", new TypeCommand(store));
        MultiKeyCommands.register(router, store);

        // expiry
        router.register("EXPIRE", new ExpireCommand("expire", store, 1_000));
        router.register("PEXPIRE", new ExpireCommand("pexpire", store, 1));
        router.register("TTL", new TtlCommand("ttl", store, false));
        router.register("PTTL", new TtlCommand("pttl", store, true));
        router.register("PERSIST", new PersistCommand(store));

        // data structures
        ListCommands.register(router, store);
        HashCommands.register(router, store);
        SetTypeCommands.register(router, store);
        SortedSetCommands.register(router, store);
        return router;
    }

    private static void runEventLoop(int port, CommandRouter router, DataStore store) throws IOException {
        // The expiry purge runs on the event-loop thread itself, between I/O events (like Redis's serverCron).
        EventLoopServer server = new EventLoopServer(port, router, store::purgeExpired, PURGE_INTERVAL_MILLIS);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "reddis-shutdown"));
        server.start();
    }

    private static void runBlocking(int port, CommandRouter router, DataStore store) throws IOException {
        ExpiryPurger purger = new ExpiryPurger(store, PURGE_INTERVAL_MILLIS);
        purger.start();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Server server = new Server(port, router, executor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            purger.close();
        }, "reddis-shutdown"));
        server.start();
    }
}