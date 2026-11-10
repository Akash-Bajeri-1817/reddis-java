package com.reddis.persistence;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.command.PersistenceCommands;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A server WITHOUT sockets: runs commands through the router and the persistence hooks exactly like the event
 * loop does, so persistence can be tested (and "restarted") quickly.
 */
final class PersistenceHarness implements AutoCloseable {

    final DataStore store;
    final Persistence persistence;
    private final CommandRouter router;

    PersistenceHarness(PersistenceConfig config, MutableClock clock) throws IOException {
        store = new DataStore(clock);
        router = CommandRegistry.standard(store);
        persistence = new Persistence(config, store, router);
        persistence.load();
        PersistenceCommands.register(router, persistence);
    }

    /** Executes one command like the event loop: execute, afterCommand, beforeReplies. */
    RespValue run(String... parts) throws IOException {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        RespValue reply = router.route(command);
        persistence.afterCommand(command, reply);
        persistence.beforeReplies();
        return reply;
    }

    static String text(RespValue reply) {
        RespValue.BulkString bulk = (RespValue.BulkString) reply;
        return bulk.isNull() ? null : new String(bulk.data(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        persistence.close();
    }
}