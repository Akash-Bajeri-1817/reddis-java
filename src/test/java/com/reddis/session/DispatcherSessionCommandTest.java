package com.reddis.session;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.RespText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatcherSessionCommandTest {

    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        CommandRouter router = CommandRegistry.standard(new DataStore());
        dispatcher = new Dispatcher(router, new PubSubBroker(), new WatchRegistry(), CommandObserver.NONE);
    }

    private static List<byte[]> command(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private String run(Session session, String... parts) throws IOException {
        List<RespValue> replies = new ArrayList<>();
        dispatcher.dispatch(session, command(parts), replies::add);
        return replies.stream().map(RespText::render).reduce((a, b) -> a + "|" + b).orElse("");
    }

    @Test
    void sessionCommandsReceiveTheirSessionAndTheirArguments() throws Exception {
        List<String> seen = new ArrayList<>();
        Session session = new Session(message -> { });
        dispatcher.registerSessionCommand("HELLO2", (s, args, out) -> {
            assertEquals(session, s);
            seen.add(new String(args.get(0), StandardCharsets.UTF_8));
            out.send(new RespValue.SimpleString("hi"));
        });

        assertEquals("hi", run(session, "hello2", "x")); // the name is case-insensitive
        assertEquals(List.of("x"), seen);
    }

    @Test
    void sessionCommandsCannotBeQueuedInsideATransaction() throws Exception {
        Session session = new Session(message -> { });
        dispatcher.registerSessionCommand("HELLO2", (s, args, out) -> out.send(new RespValue.SimpleString("hi")));

        assertEquals("OK", run(session, "MULTI"));
        assertEquals("-ERR unknown command 'HELLO2'", run(session, "HELLO2"));
        assertEquals("-EXECABORT Transaction discarded because of previous errors.", run(session, "EXEC"));
    }

    @Test
    void closeListenersAreToldWhenASessionCloses() {
        List<Session> closed = new ArrayList<>();
        dispatcher.addSessionClosedListener(closed::add);
        Session session = new Session(message -> { });

        dispatcher.sessionClosed(session);

        assertEquals(List.of(session), closed);
    }
}