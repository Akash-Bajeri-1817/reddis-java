package com.reddis.session;

import com.reddis.protocol.RespValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PubSubBrokerTest {

    private final PubSubBroker broker = new PubSubBroker();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void subscribingTwiceDoesNotDuplicateDelivery() {
        List<RespValue> inbox = new ArrayList<>();
        Session session = new Session(inbox::add);

        assertEquals(1, broker.subscribe(session, b("a")));
        assertEquals(1, broker.subscribe(session, b("a"))); // the count does not grow

        assertEquals(1, broker.publish(b("a"), b("hi")));
        assertEquals(1, inbox.size());
    }

    @Test
    void channelSubscribersAreServedInSubscriptionOrderBeforePatternSubscribers() {
        List<String> log = new ArrayList<>();
        Session viaPattern = new Session(message -> log.add("pattern"));
        Session first = new Session(message -> log.add("first"));
        Session second = new Session(message -> log.add("second"));

        broker.psubscribe(viaPattern, b("n*"));  // subscribed first, but patterns are served after channels
        broker.subscribe(first, b("news"));
        broker.subscribe(second, b("news"));

        assertEquals(3, broker.publish(b("news"), b("x")));
        assertEquals(List.of("first", "second", "pattern"), log);
    }

    @Test
    void emptyEntriesAreForgottenAndPatternsNeedAGlobMatch() {
        Session session = new Session(message -> { });
        broker.subscribe(session, b("a"));
        broker.psubscribe(session, b("x?"));
        assertEquals(1, broker.channelCount());
        assertEquals(1, broker.patternCount());

        assertEquals(0, broker.publish(b("xyz"), b("m"))); // "x?" matches exactly two bytes

        broker.unsubscribe(session, b("a"));
        broker.punsubscribe(session, b("x?"));
        assertEquals(0, broker.channelCount());
        assertEquals(0, broker.patternCount());
    }
}