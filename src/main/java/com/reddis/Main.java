package com.reddis;

import com.reddis.command.CommandRouter;
import com.reddis.command.DelCommand;
import com.reddis.command.EchoCommand;
import com.reddis.command.ExistsCommand;
import com.reddis.command.ExpireCommand;
import com.reddis.command.GetCommand;
import com.reddis.command.PersistCommand;
import com.reddis.command.PingCommand;
import com.reddis.command.SetCommand;
import com.reddis.command.TtlCommand;
import com.reddis.server.Server;
import com.reddis.storage.DataStore;
import com.reddis.storage.ExpiryPurger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static final int DEFAULT_PORT = 6379;
    private static final long PURGE_INTERVAL_MILLIS = 100; // Redis's default is also ~10 runs/second

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        DataStore store = new DataStore();

        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        router.register("EXPIRE", new ExpireCommand("expire", store, 1_000));
        router.register("PEXPIRE", new ExpireCommand("pexpire", store, 1));
        router.register("TTL", new TtlCommand("ttl", store, false));
        router.register("PTTL", new TtlCommand("pttl", store, true));
        router.register("PERSIST", new PersistCommand(store));

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