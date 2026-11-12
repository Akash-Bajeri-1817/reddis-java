package com.reddis.session;

import com.reddis.storage.ByteArrayKey;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * WATCH bookkeeping: key -> sessions watching it. touch(key) marks them dirty; EXEC then refuses to run.
 * It only knows that a key was WRITTEN, not whether the data really changed, so it can abort a transaction
 * unnecessarily but never miss a conflict. Event-loop thread only.
 */
public final class WatchRegistry {

    private final Map<ByteArrayKey, Set<Session>> watchers = new HashMap<>();

    public void watch(Session session, byte[] key) {
        ByteArrayKey k = new ByteArrayKey(key);
        if (session.watched.add(k)) {
            watchers.computeIfAbsent(k, ignored -> new LinkedHashSet<>()).add(session);
        }
    }

    /** Stops all watches of the session and clears its dirty flag. */
    public void unwatchAll(Session session) {
        for (ByteArrayKey key : session.watched) {
            Set<Session> sessions = watchers.get(key);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    watchers.remove(key);
                }
            }
        }
        session.watched.clear();
        session.watchDirty = false;
    }

    /** A key was modified (by a write command or an eviction): everyone watching it is now dirty. */
    public void touch(byte[] key) {
        Set<Session> sessions = watchers.get(new ByteArrayKey(key));
        if (sessions != null) {
            for (Session session : sessions) {
                session.watchDirty = true;
            }
        }
    }

    /** Number of distinct keys currently watched by anyone. */
    public int watchedKeyCount() {
        return watchers.size();
    }
}