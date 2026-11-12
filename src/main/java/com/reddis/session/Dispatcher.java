package com.reddis.session;

import com.reddis.command.CommandArgs;
import com.reddis.command.CommandRouter;
import com.reddis.command.WriteCommands;
import com.reddis.protocol.RespValue;
import com.reddis.storage.ByteArrayKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs one command for one client. Commands that depend on the connection's state (MULTI, EXEC, DISCARD, WATCH,
 * UNWATCH, the SUBSCRIBE family, and everything while in subscriber mode or inside a transaction) are handled
 * here; every other command goes straight to the CommandRouter.
 *
 * Because it runs on the single event-loop thread, the commands of one EXEC execute back to back with nothing
 * else in between: that is the whole isolation guarantee.
 */
public final class Dispatcher {

    private static final int MAX_QUEUED_COMMANDS = 100_000;
    private static final RespValue OK = new RespValue.SimpleString("OK");
    private static final RespValue QUEUED = new RespValue.SimpleString("QUEUED");

    private final CommandRouter router;
    private final PubSubBroker broker;
    private final WatchRegistry watches;
    private final CommandObserver observer;

    public Dispatcher(CommandRouter router, PubSubBroker broker, WatchRegistry watches, CommandObserver observer) {
        this.router = router;
        this.broker = broker;
        this.watches = watches;
        this.observer = observer;
    }

    /** Executes the command and sends every reply it produces to {@code out}. */
    public void dispatch(Session session, List<byte[]> command, ReplySink out) throws IOException {
        String name = new String(command.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        List<byte[]> args = command.subList(1, command.size());

        if (session.isSubscribed()) {
            inSubscriberMode(session, name, args, out);
            return;
        }

        switch (name) {
            case "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE" -> {
                if (session.inTransaction) {
                    session.transactionDirty = true;
                    out.send(new RespValue.RespError("ERR Command not allowed inside a transaction"));
                } else {
                    subscriptionCommand(session, name, args, out);
                }
            }
            case "MULTI" -> multi(session, out);
            case "EXEC" -> exec(session, out);
            case "DISCARD" -> discard(session, out);
            case "WATCH" -> watch(session, args, out);
            case "UNWATCH" -> {
                watches.unwatchAll(session);
                out.send(OK);
            }
            default -> {
                if (session.inTransaction) {
                    queue(session, name, command, out);
                } else {
                    out.send(runAndObserve(name, command));
                }
            }
        }
    }

    /** The client disconnected: forget its subscriptions, watches and any half-built transaction. */
    public void sessionClosed(Session session) {
        broker.removeAll(session);
        watches.unwatchAll(session);
        session.endTransaction();
    }

    // ------------------------------------------------------------ transactions

    private void multi(Session session, ReplySink out) throws IOException {
        if (session.inTransaction) {
            out.send(new RespValue.RespError("ERR MULTI calls can not be nested"));
            return;
        }
        session.beginTransaction();
        out.send(OK);
    }

    /** Queues a command (or rejects it, which makes EXEC abort). Nothing is executed yet. */
    private void queue(Session session, String name, List<byte[]> command, ReplySink out) throws IOException {
        if (!router.isRegistered(name)) {
            session.transactionDirty = true;
            out.send(new RespValue.RespError("ERR unknown command '" + name + "'"));
            return;
        }
        if (session.queued.size() >= MAX_QUEUED_COMMANDS) {
            session.transactionDirty = true;
            out.send(new RespValue.RespError("ERR transaction too large"));
            return;
        }
        session.queued.add(command);
        out.send(QUEUED);
    }

    private void exec(Session session, ReplySink out) throws IOException {
        if (!session.inTransaction) {
            out.send(new RespValue.RespError("ERR EXEC without MULTI"));
            return;
        }
        List<List<byte[]>> queued = new ArrayList<>(session.queued);
        boolean aborted = session.transactionDirty;
        boolean conflict = session.watchDirty;

        session.endTransaction();
        watches.unwatchAll(session); // before running: our own writes must not count as a conflict

        if (aborted) {
            out.send(new RespValue.RespError("EXECABORT Transaction discarded because of previous errors."));
            return;
        }
        if (conflict) {
            out.send(RespValue.RespArray.NULL); // a watched key changed: run nothing
            return;
        }

        List<RespValue> replies = new ArrayList<>(queued.size());
        for (List<byte[]> command : queued) {
            String name = new String(command.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            replies.add(runAndObserve(name, command)); // an error here is just one element of the array: no rollback
        }
        out.send(new RespValue.RespArray(replies));
    }

    private void discard(Session session, ReplySink out) throws IOException {
        if (!session.inTransaction) {
            out.send(new RespValue.RespError("ERR DISCARD without MULTI"));
            return;
        }
        session.endTransaction();
        watches.unwatchAll(session);
        out.send(OK);
    }

    private void watch(Session session, List<byte[]> keys, ReplySink out) throws IOException {
        if (session.inTransaction) {
            out.send(new RespValue.RespError("ERR WATCH inside MULTI is not allowed"));
            return;
        }
        if (keys.isEmpty()) {
            out.send(CommandArgs.wrongArgs("watch"));
            return;
        }
        for (byte[] key : keys) {
            watches.watch(session, key);
        }
        out.send(OK);
    }

    // --------------------------------------------------------------- pub/sub

    /** While subscribed, a connection may only manage its subscriptions and PING. */
    private void inSubscriberMode(Session session, String name, List<byte[]> args, ReplySink out) throws IOException {
        switch (name) {
            case "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE" -> subscriptionCommand(session, name, args, out);
            case "PING" -> out.send(new RespValue.RespArray(List.of(
                    RespValue.BulkString.of("pong"),
                    args.isEmpty() ? RespValue.BulkString.of("") : new RespValue.BulkString(args.get(0)))));
            default -> out.send(new RespValue.RespError("ERR Can't execute '" + name.toLowerCase(Locale.ROOT)
                    + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING are allowed in this context"));
        }
    }

    private void subscriptionCommand(Session session, String name, List<byte[]> args, ReplySink out) throws IOException {
        switch (name) {
            case "SUBSCRIBE" -> subscribe(session, args, out, false);
            case "PSUBSCRIBE" -> subscribe(session, args, out, true);
            case "UNSUBSCRIBE" -> unsubscribe(session, args, out, false);
            default -> unsubscribe(session, args, out, true); // PUNSUBSCRIBE
        }
    }

    /** One confirmation per channel (or pattern), each carrying the running subscription count. */
    private void subscribe(Session session, List<byte[]> targets, ReplySink out, boolean patterns) throws IOException {
        String kind = patterns ? "psubscribe" : "subscribe";
        if (targets.isEmpty()) {
            out.send(CommandArgs.wrongArgs(kind));
            return;
        }
        for (byte[] target : targets) {
            int count = patterns ? broker.psubscribe(session, target) : broker.subscribe(session, target);
            out.send(confirmation(kind, target, count));
        }
    }

    private void unsubscribe(Session session, List<byte[]> args, ReplySink out, boolean patterns) throws IOException {
        String kind = patterns ? "punsubscribe" : "unsubscribe";
        List<byte[]> targets = args.isEmpty() ? currentTargets(session, patterns) : args;
        if (targets.isEmpty()) {
            out.send(confirmation(kind, null, session.subscriptionCount())); // nothing to leave: a single nil reply
            return;
        }
        for (byte[] target : targets) {
            int count = patterns ? broker.punsubscribe(session, target) : broker.unsubscribe(session, target);
            out.send(confirmation(kind, target, count));
        }
    }

    private static List<byte[]> currentTargets(Session session, boolean patterns) {
        Set<ByteArrayKey> keys = patterns ? session.patterns : session.channels;
        List<byte[]> result = new ArrayList<>(keys.size());
        for (ByteArrayKey key : keys) {
            result.add(key.bytes());
        }
        return result; // a copy: unsubscribing edits the session's own set
    }

    private static RespValue confirmation(String kind, byte[] target, int count) {
        return new RespValue.RespArray(List.of(
                RespValue.BulkString.of(kind),
                target == null ? RespValue.BulkString.NULL : new RespValue.BulkString(target),
                new RespValue.RespInteger(count)));
    }

    // -------------------------------------------------------------- execution

    /** Runs a command through the router, lets the observer see it (AOF) and notes the keys it wrote (WATCH). */
    private RespValue runAndObserve(String name, List<byte[]> command) throws IOException {
        RespValue reply = execute(command);
        observer.afterCommand(command, reply);
        if (!(reply instanceof RespValue.RespError)) {
            for (byte[] key : WriteCommands.modifiedKeys(name, command.subList(1, command.size()))) {
                watches.touch(key);
            }
        }
        return reply;
    }

    /** A bug in one command must never stop the loop that serves every client. */
    private RespValue execute(List<byte[]> command) {
        try {
            return router.route(command);
        } catch (RuntimeException e) {
            System.err.println("Command failed: " + e);
            return new RespValue.RespError("ERR internal error");
        }
    }
}