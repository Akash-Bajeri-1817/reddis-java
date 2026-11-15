package com.reddis.session;

import com.reddis.command.CommandRegistry;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.RespText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatcherFlushAllTest {

    private final Dispatcher dispatcher = new Dispatcher(CommandRegistry.standard(new DataStore()),
            new PubSubBroker(), new WatchRegistry(), CommandObserver.NONE);

    private String run(Session session, String... parts) throws IOException {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        List<RespValue> replies = new ArrayList<>();
        dispatcher.dispatch(session, command, replies::add);
        return RespText.render(replies.get(0));
    }

    @Test // AC-4
    void flushallAbortsATransactionThatWatchesAnyKey() throws Exception {
        Session watcher = new Session(message -> { });
        Session other = new Session(message -> { });

        run(watcher, "WATCH", "somekey");
        run(other, "FLUSHALL");
        run(watcher, "MULTI");
        run(watcher, "SET", "a", "1");

        assertEquals("(nil)", run(watcher, "EXEC")); // the null array
    }
}