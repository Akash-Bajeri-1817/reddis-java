package com.reddis.server;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.PersistenceCommands;
import com.reddis.eviction.MemoryCommands;
import com.reddis.eviction.MemoryConfig;
import com.reddis.eviction.MemoryGuard;
import com.reddis.persistence.Persistence;
import com.reddis.persistence.PersistenceConfig;
import com.reddis.session.Dispatcher;
import com.reddis.session.PubSubBroker;
import com.reddis.session.PublishCommand;
import com.reddis.session.WatchRegistry;
import com.reddis.storage.DataStore;

import java.io.IOException;
import java.time.Clock;
import java.util.Random;

/** A full server (event loop, dispatcher, pub/sub, watches, persistence, memory guard) on a free port. */
final class TestServer implements AutoCloseable {

    final DataStore store;
    final Persistence persistence;
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

        MemoryGuard guard = new MemoryGuard(store, MemoryConfig.defaults(), Clock.systemUTC(), new Random(), key -> {
            persistence.recordEviction(key);
            watches.touch(key);
        });
        router.setGuard(guard);
        MemoryCommands.register(router, guard, store);

        Dispatcher dispatcher = new Dispatcher(router, broker, watches, persistence::afterCommand);
        server = new EventLoopServer(0, dispatcher, () -> {
            store.purgeExpired();
            persistence.cron();
        }, 20, persistence);
        server.start();
        port = server.getPort();
    }

    @Override
    public void close() {
        server.stop();
        persistence.close();
    }
}