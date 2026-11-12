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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dispatcher without any sockets: several Sessions talk to one server core. */
class DispatcherTest {

    private static final String WRONGTYPE = "-WRONGTYPE Operation against a key holding the wrong kind of value";

    private PubSubBroker broker;
    private WatchRegistry watches;
    private Dispatcher dispatcher;
    private final List<String> observed = new ArrayList<>(); // names of the commands the observer was told about

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        CommandRouter router = CommandRegistry.standard(store);
        broker = new PubSubBroker();
        watches = new WatchRegistry();
        router.register("PUBLISH", new PublishCommand(broker));
        dispatcher = new Dispatcher(router, broker, watches,
                (command, reply) -> observed.add(new String(command.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT)));
    }

    private static List<byte[]> command(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    /** One simulated connection. */
    private final class Client {
        final Session session;
        final List<RespValue> pushes = new ArrayList<>();

        Client() {
            this.session = new Session(pushes::add);
        }

        /** Runs a command and returns everything sent back to this client for it, rendered as text. */
        List<String> run(String... parts) throws IOException {
            List<RespValue> replies = new ArrayList<>();
            dispatcher.dispatch(session, command(parts), replies::add);
            return replies.stream().map(RespText::render).toList();
        }

        /** Runs a command that produces exactly one reply. */
        String one(String... parts) throws IOException {
            List<String> replies = run(parts);
            assertEquals(1, replies.size(), "replies: " + replies);
            return replies.get(0);
        }

        List<String> pushed() {
            return pushes.stream().map(RespText::render).toList();
        }
    }

    // ------------------------------------------------------------ transactions

    @Test // AC-7
    void multiQueuesCommandsAndExecRunsThemInOrder() throws Exception {
        Client c = new Client();
        assertEquals("OK", c.one("MULTI"));
        assertEquals("QUEUED", c.one("SET", "a", "1"));
        assertEquals("QUEUED", c.one("GET", "a"));
        assertEquals("[OK,1]", c.one("EXEC"));
    }

    @Test // AC-7
    void nothingRunsBeforeExec() throws Exception {
        Client c1 = new Client();
        Client c2 = new Client();
        c1.one("MULTI");
        c1.one("SET", "a", "1");
        assertEquals("(nil)", c2.one("GET", "a")); // still queued, not executed
        c1.one("EXEC");
        assertEquals("1", c2.one("GET", "a"));
    }

    @Test
    void discardDropsTheQueue() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        c.one("SET", "a", "1");
        assertEquals("OK", c.one("DISCARD"));
        assertEquals("(nil)", c.one("GET", "a"));
        assertEquals("-ERR EXEC without MULTI", c.one("EXEC"));
    }

    @Test
    void execAndDiscardWithoutMultiAreErrors() throws Exception {
        Client c = new Client();
        assertEquals("-ERR EXEC without MULTI", c.one("EXEC"));
        assertEquals("-ERR DISCARD without MULTI", c.one("DISCARD"));
    }

    @Test
    void aNestedMultiIsAnErrorButKeepsTheTransaction() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        assertEquals("-ERR MULTI calls can not be nested", c.one("MULTI"));
        assertEquals("QUEUED", c.one("SET", "a", "1"));
        assertEquals("[OK]", c.one("EXEC"));
    }

    @Test // AC-8
    void errorsInsideATransactionDoNotRollBackTheOtherCommands() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        c.one("SET", "a", "1");
        c.one("LPUSH", "a", "x"); // a is a string: fails when it runs
        c.one("SET", "b", "2");
        assertEquals("[OK," + WRONGTYPE + ",OK]", c.one("EXEC"));

        assertEquals("1", c.one("GET", "a")); // the commands before AND after the failure took effect
        assertEquals("2", c.one("GET", "b"));
    }

    @Test // AC-8
    void anUnknownCommandAbortsTheWholeTransaction() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        c.one("SET", "a", "1");
        assertEquals("-ERR unknown command 'FLY'", c.one("FLY"));
        assertEquals("-EXECABORT Transaction discarded because of previous errors.", c.one("EXEC"));
        assertEquals("(nil)", c.one("GET", "a")); // nothing ran
    }

    @Test // AC-9
    void aWatchedKeyChangedByAnotherClientMakesExecReturnNil() throws Exception {
        Client c1 = new Client();
        Client c2 = new Client();
        assertEquals("OK", c1.one("WATCH", "k"));
        c2.one("SET", "k", "theirs");

        c1.one("MULTI");
        assertEquals("QUEUED", c1.one("SET", "k", "mine"));
        assertEquals("(nil)", c1.one("EXEC"));
        assertEquals("theirs", c1.one("GET", "k")); // c1's queued SET never ran
    }

    @Test // AC-9
    void anUntouchedWatchedKeyLetsExecRun() throws Exception {
        Client c = new Client();
        c.one("WATCH", "k");
        c.one("MULTI");
        c.one("SET", "k", "mine");
        assertEquals("[OK]", c.one("EXEC"));
        assertEquals("mine", c.one("GET", "k"));
    }

    @Test // AC-9
    void execClearsTheWatches() throws Exception {
        Client c1 = new Client();
        Client c2 = new Client();
        c1.one("WATCH", "k");
        c1.one("MULTI");
        c1.one("SET", "x", "1");
        c1.one("EXEC");
        assertEquals(0, watches.watchedKeyCount());

        c2.one("SET", "k", "changed"); // nobody watches k any more
        c1.one("MULTI");
        c1.one("SET", "y", "1");
        assertEquals("[OK]", c1.one("EXEC"));
    }

    @Test // AC-9
    void unwatchAndDiscardClearTheWatches() throws Exception {
        Client c1 = new Client();
        Client c2 = new Client();
        c1.one("WATCH", "k");
        assertEquals("OK", c1.one("UNWATCH"));
        c2.one("SET", "k", "v");
        c1.one("MULTI");
        assertEquals("[]", c1.one("EXEC")); // an empty transaction is fine

        c1.one("WATCH", "k");
        c1.one("MULTI");
        c1.one("DISCARD");
        c2.one("SET", "k", "again");
        c1.one("MULTI");
        c1.one("SET", "z", "1");
        assertEquals("[OK]", c1.one("EXEC"));
    }

    @Test
    void watchInsideMultiIsAnError() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        assertEquals("-ERR WATCH inside MULTI is not allowed", c.one("WATCH", "k"));
    }

    @Test // AC-9: EXEC unwatches BEFORE running the queue
    void modifyingAWatchedKeyInsideYourOwnTransactionDoesNotAbortIt() throws Exception {
        Client c = new Client();
        c.one("WATCH", "k");
        c.one("MULTI");
        c.one("SET", "k", "v");
        assertEquals("[OK]", c.one("EXEC"));
    }

    @Test // AC-9: this is what the memory guard's eviction listener does
    void anEvictionOfAWatchedKeyCountsAsAChange() throws Exception {
        Client c = new Client();
        c.one("WATCH", "k");
        watches.touch("k".getBytes(StandardCharsets.UTF_8));
        c.one("MULTI");
        c.one("SET", "a", "1");
        assertEquals("(nil)", c.one("EXEC"));
    }

    @Test
    void subscriptionCommandsAreRejectedInsideAMultiAndAbortTheTransaction() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        assertEquals("-ERR Command not allowed inside a transaction", c.one("SUBSCRIBE", "news"));
        assertEquals("-EXECABORT Transaction discarded because of previous errors.", c.one("EXEC"));
    }

    @Test // AC-10
    void theObserverSeesEachExecutedCommandButNotTheTransactionControlCommands() throws Exception {
        Client c = new Client();
        c.one("MULTI");
        c.one("SET", "a", "1");
        c.one("RPUSH", "l", "x");
        c.one("EXEC");
        c.one("GET", "a");
        assertEquals(List.of("SET", "RPUSH", "GET"), observed);

        observed.clear();
        c.one("MULTI");
        c.one("SET", "b", "2");
        c.one("DISCARD");
        assertTrue(observed.isEmpty()); // a discarded transaction must never reach the log
    }

    // ----------------------------------------------------------------- pub/sub

    @Test // AC-1
    void subscribeRepliesOncePerChannelWithTheRunningCount() throws Exception {
        Client c = new Client();
        assertEquals(List.of("[subscribe,a,1]", "[subscribe,b,2]"), c.run("SUBSCRIBE", "a", "b"));
    }

    @Test // AC-3
    void subscriberModeRestrictsCommands() throws Exception {
        Client c = new Client();
        c.one("SUBSCRIBE", "a");
        assertEquals("-ERR Can't execute 'get': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING are allowed in this context",
                c.one("GET", "k"));
        assertEquals("[pong,]", c.one("PING"));
        assertEquals("[pong,hi]", c.one("PING", "hi"));
    }

    @Test
    void unsubscribingFromEverythingLeavesSubscriberMode() throws Exception {
        Client c = new Client();
        c.one("SUBSCRIBE", "a");
        assertEquals("[unsubscribe,a,0]", c.one("UNSUBSCRIBE", "a"));
        assertEquals("(nil)", c.one("GET", "k")); // normal commands work again
        assertEquals("[unsubscribe,(nil),0]", c.one("UNSUBSCRIBE")); // nothing to leave: a single nil reply
    }

    @Test
    void unsubscribeWithoutArgumentsLeavesEveryChannel() throws Exception {
        Client c = new Client();
        c.run("SUBSCRIBE", "a", "b");
        assertEquals(List.of("[unsubscribe,a,1]", "[unsubscribe,b,0]"), c.run("UNSUBSCRIBE"));
    }

    @Test // AC-2
    void publishDeliversToSubscribersAndReturnsTheReceiverCount() throws Exception {
        Client subscriber = new Client();
        Client publisher = new Client();
        subscriber.run("SUBSCRIBE", "news");

        assertEquals("1", publisher.one("PUBLISH", "news", "hello"));
        assertEquals(List.of("[message,news,hello]"), subscriber.pushed());
        assertEquals("0", publisher.one("PUBLISH", "other", "x"));
    }

    @Test // AC-2, AC-4
    void patternSubscribersReceivePmessages() throws Exception {
        Client subscriber = new Client();
        Client publisher = new Client();
        assertEquals("[psubscribe,news.*,1]", subscriber.one("PSUBSCRIBE", "news.*"));

        assertEquals("1", publisher.one("PUBLISH", "news.tech", "hi"));
        assertEquals("0", publisher.one("PUBLISH", "weather", "rain"));
        assertEquals(List.of("[pmessage,news.*,news.tech,hi]"), subscriber.pushed());
    }

    @Test
    void aClientSubscribedByChannelAndPatternGetsBothMessages() throws Exception {
        Client subscriber = new Client();
        Client publisher = new Client();
        subscriber.run("SUBSCRIBE", "news.tech");
        subscriber.run("PSUBSCRIBE", "news.*");

        assertEquals("2", publisher.one("PUBLISH", "news.tech", "hi"));
        assertEquals(List.of("[message,news.tech,hi]", "[pmessage,news.*,news.tech,hi]"), subscriber.pushed());
    }

    @Test // AC-5
    void closingASessionRemovesItsSubscriptionsAndWatches() throws Exception {
        Client c = new Client();
        c.run("SUBSCRIBE", "a");
        c.run("PSUBSCRIBE", "p*");
        Client watcher = new Client();
        watcher.one("WATCH", "k");

        dispatcher.sessionClosed(c.session);
        dispatcher.sessionClosed(watcher.session);

        assertEquals(0, broker.channelCount());
        assertEquals(0, broker.patternCount());
        assertEquals(0, watches.watchedKeyCount());
        assertEquals("0", new Client().one("PUBLISH", "a", "x"));
    }

    @Test
    void publishInsideATransactionIsQueuedAndDeliveredOnExec() throws Exception {
        Client subscriber = new Client();
        Client publisher = new Client();
        subscriber.run("SUBSCRIBE", "news");

        publisher.one("MULTI");
        assertEquals("QUEUED", publisher.one("PUBLISH", "news", "x"));
        assertTrue(subscriber.pushed().isEmpty()); // not delivered yet

        assertEquals("[1]", publisher.one("EXEC"));
        assertEquals(List.of("[message,news,x]"), subscriber.pushed());
    }
}