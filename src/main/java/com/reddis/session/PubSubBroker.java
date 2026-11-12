package com.reddis.session;

import com.reddis.protocol.RespValue;
import com.reddis.storage.ByteArrayKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who is subscribed to what, and message delivery. A publish appends the message to each subscriber's own output
 * buffer (through its Session). Everything runs on the event-loop thread, so no locks are needed.
 */
public final class PubSubBroker {

    private final Map<ByteArrayKey, Set<Session>> channelSubscribers = new HashMap<>();
    private final Map<ByteArrayKey, Set<Session>> patternSubscribers = new LinkedHashMap<>();

    /** @return the session's total number of subscriptions (channels + patterns) afterwards */
    public int subscribe(Session session, byte[] channel) {
        ByteArrayKey key = new ByteArrayKey(channel);
        if (session.channels.add(key)) {
            channelSubscribers.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(session);
        }
        return session.subscriptionCount();
    }

    public int unsubscribe(Session session, byte[] channel) {
        ByteArrayKey key = new ByteArrayKey(channel);
        if (session.channels.remove(key)) {
            removeFrom(channelSubscribers, key, session);
        }
        return session.subscriptionCount();
    }

    public int psubscribe(Session session, byte[] pattern) {
        ByteArrayKey key = new ByteArrayKey(pattern);
        if (session.patterns.add(key)) {
            patternSubscribers.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(session);
        }
        return session.subscriptionCount();
    }

    public int punsubscribe(Session session, byte[] pattern) {
        ByteArrayKey key = new ByteArrayKey(pattern);
        if (session.patterns.remove(key)) {
            removeFrom(patternSubscribers, key, session);
        }
        return session.subscriptionCount();
    }

    /** Forgets every subscription of a session (it disconnected). Sends nothing. */
    public void removeAll(Session session) {
        for (ByteArrayKey channel : session.channels) {
            removeFrom(channelSubscribers, channel, session);
        }
        session.channels.clear();
        for (ByteArrayKey pattern : session.patterns) {
            removeFrom(patternSubscribers, pattern, session);
        }
        session.patterns.clear();
    }

    /**
     * Delivers the message to every subscriber of the channel and of every matching pattern.
     *
     * @return the number of deliveries (a client subscribed twice-over receives, and counts, twice)
     */
    public int publish(byte[] channel, byte[] message) {
        ByteArrayKey key = new ByteArrayKey(channel);

        // Decide WHO receives BEFORE delivering: delivery can close a slow subscriber, which edits these maps.
        List<Session> direct = new ArrayList<>(channelSubscribers.getOrDefault(key, Set.of()));
        List<Map.Entry<byte[], List<Session>>> matched = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, Set<Session>> entry : patternSubscribers.entrySet()) {
            if (GlobMatcher.matches(entry.getKey().bytes(), channel)) {
                matched.add(Map.entry(entry.getKey().bytes(), new ArrayList<>(entry.getValue())));
            }
        }

        int receivers = 0;
        if (!direct.isEmpty()) {
            RespValue push = array("message", channel, message);
            for (Session session : direct) {
                session.push(push);
                receivers++;
            }
        }
        for (Map.Entry<byte[], List<Session>> entry : matched) {
            RespValue push = array("pmessage", entry.getKey(), channel, message);
            for (Session session : entry.getValue()) {
                session.push(push);
                receivers++;
            }
        }
        return receivers;
    }

    /** Number of channels with at least one subscriber (for tests and diagnostics). */
    public int channelCount() {
        return channelSubscribers.size();
    }

    /** Number of patterns with at least one subscriber. */
    public int patternCount() {
        return patternSubscribers.size();
    }

    private static void removeFrom(Map<ByteArrayKey, Set<Session>> map, ByteArrayKey key, Session session) {
        Set<Session> sessions = map.get(key);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                map.remove(key);
            }
        }
    }

    private static RespValue array(String kind, byte[]... parts) {
        List<RespValue> elements = new ArrayList<>(parts.length + 1);
        elements.add(RespValue.BulkString.of(kind));
        for (byte[] part : parts) {
            elements.add(new RespValue.BulkString(part));
        }
        return new RespValue.RespArray(elements);
    }
}