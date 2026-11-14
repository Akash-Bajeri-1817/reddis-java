package com.reddis.replication;

import com.reddis.persistence.CommandPropagator;
import com.reddis.persistence.SnapshotCodec;
import com.reddis.protocol.RespValue;
import com.reddis.server.CommandHooks;
import com.reddis.session.CommandObserver;
import com.reddis.session.Dispatcher;
import com.reddis.session.ReplySink;
import com.reddis.session.Session;
import com.reddis.storage.DataStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Replication for one server, in whichever role it currently has.
 *
 * As a MASTER it watches the commands the dispatcher executes (it is a CommandObserver), turns them into stream
 * records with the same CommandPropagator the AOF uses, and lets ReplicationMaster feed them to the replicas.
 *
 * As a REPLICA it runs a ReplicaLink thread that talks to the master and hands everything over to the event loop,
 * where the snapshot is loaded and the commands are applied through the normal Dispatcher.
 */
public final class Replication implements CommandObserver, ReplicaState {

    private static final ReplySink DISCARD = reply -> { };

    private final DataStore store;
    private final CommandPropagator propagator;
    private final ReplicationMaster master;
    private final Path workDir;
    private final Session linkSession = new Session(message -> { }); // the identity of "the master" when applying

    private Dispatcher dispatcher;
    private Executor loop;
    private CommandHooks hooks;
    private Runnable datasetReplaced;

    private volatile String masterHost;       // null while this server is a master
    private volatile int masterPort;
    private volatile ReplicaLink link;
    private volatile boolean linkUp;
    private volatile boolean applying;
    private volatile String replicaReplId;    // the master's replication ID, once known
    private volatile long receivedOffset = -1; // stream bytes received from the master (used to reconnect)
    private volatile long appliedOffset = -1;  // stream bytes applied to the data (used for ACKs and INFO)

    public Replication(DataStore store, ReplicationConfig config, Path workDir) {
        this.store = store;
        this.propagator = new CommandPropagator(store);
        this.master = new ReplicationMaster(store, config.backlogBytes());
        this.workDir = workDir;
    }

    /**
     * Connects replication to the running server. Call once, before the server starts.
     *
     * @param loop            runs tasks on the event-loop thread (EventLoopServer::execute)
     * @param hooks           flushes the replica's own AOF after each applied batch
     * @param datasetReplaced told when a full resync replaced the whole dataset
     */
    public void attach(Dispatcher dispatcher, Executor loop, CommandHooks hooks, Runnable datasetReplaced) {
        this.dispatcher = dispatcher;
        this.loop = loop;
        this.hooks = hooks;
        this.datasetReplaced = datasetReplaced;
        dispatcher.registerSessionCommand("PSYNC", this::psync);
        dispatcher.registerSessionCommand("REPLCONF", this::replconf);
        dispatcher.addSessionClosedListener(master::replicaGone);
    }

    // ------------------------------------------------------------- master role

    /** Called by the dispatcher after every executed command. */
    @Override
    public void afterCommand(List<byte[]> command, RespValue reply) {
        if (isReplica()) {
            return; // a replica does not produce a stream of its own (no chained replication)
        }
        for (List<byte[]> record : propagator.propagate(command, reply)) {
            master.propagate(record);
        }
    }

    /** An eviction is a write nobody asked for: replicas must delete the key too. */
    public void recordEviction(byte[] key) {
        if (!isReplica()) {
            master.propagate(List.of(com.reddis.persistence.AofFormat.ascii("DEL"), key));
        }
    }

    private void psync(Session session, List<byte[]> args, ReplySink out) throws IOException {
        if (isReplica()) {
            out.send(new RespValue.RespError(
                    "ERR PSYNC is not supported by a replica (chained replication is not supported)"));
            return;
        }
        master.psync(session, args, out);
    }

    private void replconf(Session session, List<byte[]> args, ReplySink out) throws IOException {
        master.replconf(session, args, out);
    }

    // ------------------------------------------------------------ role changes

    /** REPLICAOF host port: start (or redirect) following a master. */
    public synchronized void replicaOf(String host, int port) {
        if (master.replicaCount() > 0) {
            throw new IllegalStateException(
                    "cannot become a replica while serving replicas (chained replication is not supported)");
        }
        boolean sameMaster = host.equals(masterHost) && port == masterPort;
        if (sameMaster && link != null) {
            return;
        }
        stopLink();
        if (!sameMaster) {
            replicaReplId = null; // a different master means a different history: start from a full copy
            receivedOffset = -1;
            appliedOffset = -1;
        }
        masterHost = host;
        masterPort = port;
        link = new ReplicaLink(host, port, this);
        link.start();
    }

    /** REPLICAOF NO ONE: stop following and become a master with a NEW replication history. */
    public synchronized void promote() {
        if (!isReplica()) {
            return;
        }
        long keepOffset = Math.max(0, appliedOffset);
        stopLink();
        masterHost = null;
        replicaReplId = null;
        receivedOffset = -1;
        appliedOffset = -1;
        master.reset(keepOffset);
    }

    /** Shutdown: stop the link thread. */
    public synchronized void stop() {
        stopLink();
    }

    /** Closes the connection to the master; the link reconnects by itself (and usually resyncs partially). */
    public void dropConnection() {
        ReplicaLink current = link;
        if (current != null) {
            current.dropConnection();
        }
    }

    private void stopLink() {
        ReplicaLink current = link;
        link = null;
        linkUp = false;
        if (current != null) {
            current.stop();
        }
    }

    // ------------------------------------------- callbacks from the link thread

    String syncReplId() {
        String id = replicaReplId;
        return id == null ? "?" : id;
    }

    long syncOffset() {
        return receivedOffset;
    }

    long ackOffset() {
        return appliedOffset;
    }

    void onFullSync(ReplicaLink from, byte[] snapshot, String replId, long offset) throws IOException {
        if (from != link) {
            return;
        }
        Files.createDirectories(workDir);
        Path file = workDir.resolve("replication-sync-" + System.nanoTime() + ".rdb");
        Files.write(file, snapshot); // like real Redis, the received snapshot goes through the disk
        replicaReplId = replId;
        receivedOffset = offset;
        loop.execute(() -> loadSnapshot(from, file, replId, offset));
    }

    void onContinue(ReplicaLink from, String replId) {
        if (from == link && replId != null) {
            replicaReplId = replId;
        }
    }

    void onCommands(ReplicaLink from, List<List<byte[]>> commands, long bytes) {
        if (from != link) {
            return;
        }
        receivedOffset += bytes;
        loop.execute(() -> applyBatch(from, commands, bytes));
    }

    void onLinkUp(ReplicaLink from) {
        if (from == link) {
            linkUp = true;
        }
    }

    void onLinkDown(ReplicaLink from) {
        if (from == link) {
            linkUp = false;
        }
    }

    // ------------------------------------------ work on the event-loop thread

    private void loadSnapshot(ReplicaLink from, Path file, String replId, long offset) {
        try {
            if (from != link) {
                return; // we were redirected or promoted meanwhile
            }
            store.clear(); // a full resync replaces EVERYTHING the replica had
            SnapshotCodec.load(file, store);
            appliedOffset = offset;
            datasetReplaced.run();
            System.out.println("Replica: full resync from " + masterHost + ":" + masterPort
                    + " complete (offset " + offset + ")");
        } catch (IOException | RuntimeException e) {
            System.err.println("Replica: could not load the master's snapshot: " + e);
            replicaReplId = null;
            receivedOffset = -1;
            appliedOffset = -1;
            from.dropConnection(); // reconnect and start over with a full resync
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a leftover temp file is harmless
            }
        }
    }

    private void applyBatch(ReplicaLink from, List<List<byte[]>> commands, long bytes) {
        if (from != link) {
            return;
        }
        applying = true; // the read-only guard lets the master's writes through
        try {
            for (List<byte[]> command : commands) {
                dispatcher.dispatch(linkSession, command, DISCARD); // same path as a client: AOF, watchers, ...
            }
            hooks.beforeReplies(); // flush this replica's own AOF
            appliedOffset += bytes;
        } catch (IOException | RuntimeException e) {
            System.err.println("Replica: failed to apply the master's stream: " + e);
            from.dropConnection();
        } finally {
            applying = false;
        }
    }

    // ------------------------------------------------------------------ queries

    @Override
    public boolean isReplica() {
        return masterHost != null;
    }

    @Override
    public boolean isApplying() {
        return applying;
    }

    public String masterHost() {
        return masterHost;
    }

    public int masterPort() {
        return masterPort;
    }

    public boolean isLinkUp() {
        return linkUp;
    }

    public long appliedOffset() {
        return appliedOffset;
    }

    public long masterOffset() {
        return master.offset();
    }

    /** The text of INFO replication. */
    public String infoText() {
        StringBuilder text = new StringBuilder("# Replication\r\n");
        if (isReplica()) {
            String id = replicaReplId;
            text.append("role:slave\r\n")
                    .append("master_host:").append(masterHost).append("\r\n")
                    .append("master_port:").append(masterPort).append("\r\n")
                    .append("master_link_status:").append(linkUp ? "up" : "down").append("\r\n")
                    .append("slave_repl_offset:").append(appliedOffset).append("\r\n")
                    .append("master_replid:").append(id == null ? "?" : id).append("\r\n");
        } else {
            text.append(master.infoText());
        }
        return text.toString();
    }
}