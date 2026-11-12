package com.reddis.session;

import com.reddis.protocol.RespValue;
import com.reddis.storage.ByteArrayKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the server remembers about ONE connection between commands: the MULTI queue, the WATCHed keys and the
 * subscriptions. Only the event-loop thread touches it. The fields are package-private on purpose: Dispatcher,
 * PubSubBroker and WatchRegistry are the only code that changes them.
 */
public final class Session {

    private final MessageSink sink;

    // MULTI / EXEC
    boolean inTransaction;
    boolean transactionDirty; // a command could not be queued: EXEC will abort
    final List<List<byte[]>> queued = new ArrayList<>();

    // WATCH
    final Set<ByteArrayKey> watched = new LinkedHashSet<>();
    boolean watchDirty;       // a watched key was written since WATCH

    // pub/sub (insertion order = subscription order)
    final Set<ByteArrayKey> channels = new LinkedHashSet<>();
    final Set<ByteArrayKey> patterns = new LinkedHashSet<>();

    public Session(MessageSink sink) {
        this.sink = sink;
    }

    /** Delivers a pushed message (pub/sub) to this client. */
    void push(RespValue message) {
        sink.send(message);
    }

    void beginTransaction() {
        inTransaction = true;
        transactionDirty = false;
        queued.clear();
    }

    void endTransaction() {
        inTransaction = false;
        transactionDirty = false;
        queued.clear();
    }

    public boolean isSubscribed() {
        return !channels.isEmpty() || !patterns.isEmpty();
    }

    public int subscriptionCount() {
        return channels.size() + patterns.size();
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    public int queuedCommands() {
        return queued.size();
    }
}