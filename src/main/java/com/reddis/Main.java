package com.reddis;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.PersistenceCommands;
import com.reddis.eviction.MemoryCommands;
import com.reddis.eviction.MemoryGuard;
import com.reddis.persistence.Persistence;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.server.EventLoopServer;
import com.reddis.server.Server;
import com.reddis.session.Dispatcher;
import com.reddis.session.PubSubBroker;
import com.reddis.session.PublishCommand;
import com.reddis.session.WatchRegistry;
import com.reddis.storage.DataStore;
import com.reddis.storage.ExpiryPurger;

import java.io.IOException;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static final long CRON_INTERVAL_MILLIS = 100; // Redis's default is also ~10 runs per second

    public static void main(String[] args) throws IOException {
        ServerConfig config;
        try {
            config = ServerConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(ServerConfig.USAGE);
            System.exit(1);
            return;
        }

        DataStore store = new DataStore();
        CommandRouter router = CommandRegistry.standard(store);

        if (config.blocking()) {
            runBlocking(config, router, store);
        } else {
            runEventLoop(config, router, store);
        }
    }

    private static void runEventLoop(ServerConfig config, CommandRouter router, DataStore store) throws IOException {
        Persistence persistence = new Persistence(config.persistence(), store, router);
        persistence.load(); // restore the dataset BEFORE accepting any client
        PersistenceCommands.register(router, persistence);

        PubSubBroker broker = new PubSubBroker();
        WatchRegistry watches = new WatchRegistry();
        router.register("PUBLISH", new PublishCommand(broker));

        // Installed only AFTER the load: replaying a dataset must never trigger evictions.
        MemoryGuard guard = new MemoryGuard(store, config.memory(), Clock.systemUTC(), new Random(), key -> {
            persistence.recordEviction(key); // logged to the AOF as DEL
            watches.touch(key);              // an evicted key counts as modified for WATCH
        });
        router.setGuard(guard);
        MemoryCommands.register(router, guard, store);

        // The dispatcher runs every command; it tells the persistence layer about each executed command.
        Dispatcher dispatcher = new Dispatcher(router, broker, watches, persistence::afterCommand);

        // Expiry purging and autosave run on the event-loop thread, between I/O events (like Redis's serverCron).
        Runnable cron = () -> {
            store.purgeExpired();
            persistence.cron();
        };
        EventLoopServer server = new EventLoopServer(config.port(), dispatcher, cron, CRON_INTERVAL_MILLIS, persistence);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();       // first: no more commands
            persistence.close(); // then: flush the log, finish background saves, final snapshot
        }, "reddis-shutdown"));
        server.start();
    }

    private static void runBlocking(ServerConfig config, CommandRouter router, DataStore store) throws IOException {
        PersistenceConfig persistence = config.persistence();
        if (persistence.appendOnly() || persistence.saveIntervalSeconds() > 0 || config.memory().maxMemoryBytes() > 0) {
            System.err.println("Warning: persistence, maxmemory, pub/sub and transactions require the event-loop "
                    + "server; ignored with --blocking");
        }
        ExpiryPurger purger = new ExpiryPurger(store, CRON_INTERVAL_MILLIS);
        purger.start();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Server server = new Server(config.port(), router, executor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            purger.close();
        }, "reddis-shutdown"));
        server.start();
    }
}