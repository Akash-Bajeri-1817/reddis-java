package com.reddis.server;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.CompositeGuard;
import com.reddis.command.PersistenceCommands;
import com.reddis.eviction.MemoryCommands;
import com.reddis.eviction.MemoryConfig;
import com.reddis.eviction.MemoryGuard;
import com.reddis.persistence.Persistence;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.replication.ReadOnlyGuard;
import com.reddis.replication.Replication;
import com.reddis.replication.ReplicationCommands;
import com.reddis.replication.ReplicationConfig;
import com.reddis.session.CommandObserver;
import com.reddis.session.Dispatcher;
import com.reddis.session.PubSubBroker;
import com.reddis.session.PublishCommand;
import com.reddis.session.WatchRegistry;
import com.reddis.storage.DataStore;

import java.io.IOException;
import java.time.Clock;
import java.util.Random;

/** A full server (event loop, dispatcher, pub/sub, watches, persistence, memory guard, replication) on a free port. */
final class TestServer implements AutoCloseable {

    final DataStore store;
    final Persistence persistence;
    final Replication replication;
    final EventLoopServer server;
    final int port;

    TestServer(PersistenceConfig config) throws IOException {
        store = new DataStore();
        CommandRouter router = CommandRegistry.standard(store);
        persistence = new Persistence(config, store, router);
        persistence.load();
        PersistenceCommands.register(router, persistence);

        PubSubBroker broker = new PubSubBroker();
        WatchRegistry watches = new WatchRegistry();
        router.register("PUBLISH", new PublishCommand(broker));

        replication = new Replication(store, ReplicationConfig.defaults(), config.dir());
        MemoryGuard memoryGuard = new MemoryGuard(store, MemoryConfig.defaults(), Clock.systemUTC(), new Random(), key -> {
            persistence.recordEviction(key);
            watches.touch(key);
            replication.recordEviction(key);
        });
        router.setGuard(new CompositeGuard(
                new ReadOnlyGuard(replication),
                name -> replication.isApplying() ? null : memoryGuard.beforeExecute(name)));
        MemoryCommands.register(router, memoryGuard, store);
        ReplicationCommands.register(router, replication);

        CommandObserver observer = (command, reply) -> {
            persistence.afterCommand(command, reply);
            replication.afterCommand(command, reply);
        };
        Dispatcher dispatcher = new Dispatcher(router, broker, watches, observer);
        server = new EventLoopServer(0, dispatcher, () -> {
            store.purgeExpired();
            persistence.cron();
        }, 20, persistence);
        replication.attach(dispatcher, server, persistence, persistence::onDatasetReplaced);
        server.start();
        port = server.getPort();
    }

    @Override
    public void close() {
        replication.stop();
        server.stop();
        persistence.close();
    }
}