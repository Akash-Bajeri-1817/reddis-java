package com.reddis;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.CompositeGuard;
import com.reddis.command.PersistenceCommands;
import com.reddis.eviction.MemoryCommands;
import com.reddis.eviction.MemoryGuard;
import com.reddis.persistence.Persistence;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.replication.ReadOnlyGuard;
import com.reddis.replication.Replication;
import com.reddis.replication.ReplicationCommands;
import com.reddis.server.EventLoopServer;
import com.reddis.server.Server;
import com.reddis.session.CommandObserver;
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

        Replication replication = new Replication(store, config.replication(), config.persistence().dir());

        // Installed only AFTER the load: replaying a dataset must never trigger evictions.
        MemoryGuard memoryGuard = new MemoryGuard(store, config.memory(), Clock.systemUTC(), new Random(), key -> {
            persistence.recordEviction(key); // logged to the AOF as DEL
            watches.touch(key);              // an evicted key counts as modified for WATCH
            replication.recordEviction(key); // and replicas delete it too
        });
        router.setGuard(new CompositeGuard(
                new ReadOnlyGuard(replication),                                   // replicas refuse client writes
                name -> replication.isApplying() ? null : memoryGuard.beforeExecute(name))); // a replica applies as is
        MemoryCommands.register(router, memoryGuard, store);
        ReplicationCommands.register(router, replication); // after MemoryCommands: it decorates INFO

        // Every executed command goes to the AOF and, on a master, to the replicas.
        CommandObserver observer = (command, reply) -> {
            persistence.afterCommand(command, reply);
            replication.afterCommand(command, reply);
        };
        Dispatcher dispatcher = new Dispatcher(router, broker, watches, observer);

        // Expiry purging and autosave run on the event-loop thread, between I/O events (like Redis's serverCron).
        Runnable cron = () -> {
            store.purgeExpired();
            persistence.cron();
        };
        EventLoopServer server = new EventLoopServer(config.port(), dispatcher, cron, CRON_INTERVAL_MILLIS, persistence);
        replication.attach(dispatcher, server, persistence, persistence::onDatasetReplaced);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            replication.stop();  // first: stop following the master
            server.stop();       // then: no more commands
            persistence.close(); // finally: flush the log, finish background saves, final snapshot
        }, "reddis-shutdown"));
        server.start();

        if (config.replication().isReplica()) {
            replication.replicaOf(config.replication().masterHost(), config.replication().masterPort());
        }
    }

    private static void runBlocking(ServerConfig config, CommandRouter router, DataStore store) throws IOException {
        PersistenceConfig persistence = config.persistence();
        if (persistence.appendOnly() || persistence.saveIntervalSeconds() > 0 || config.memory().maxMemoryBytes() > 0
                || config.replication().isReplica()) {
            System.err.println("Warning: persistence, maxmemory, pub/sub, transactions and replication require the "
                    + "event-loop server; ignored with --blocking");
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