package com.reddis.server;

import com.reddis.command.CommandRouter;
import com.reddis.protocol.ProtocolException;
import com.reddis.protocol.RespDecoder;
import com.reddis.protocol.RespValue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A single-threaded, non-blocking server: ONE thread accepts connections, reads, executes every command
 * and writes replies (the same model as Redis). Because only this thread runs commands, each command,
 * including multi-key ones, is atomic.
 */
public class EventLoopServer {

    private final int port;
    private final CommandRouter router;
    private final Runnable periodicTask;
    private final long periodMillis;
    private final RespDecoder decoder = new RespDecoder();

    private volatile boolean running;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread loopThread;

    /**
     * @param port         0 means "any free port" (see getPort())
     * @param periodicTask runs on the event-loop thread every periodMillis (we use it to purge expired keys)
     */
    public EventLoopServer(int port, CommandRouter router, Runnable periodicTask, long periodMillis) {
        this.port = port;
        this.router = router;
        this.periodicTask = periodicTask;
        this.periodMillis = periodMillis;
    }

    /** Binds the port and starts the loop thread, then returns. */
    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server already started");
        }
        selector = Selector.open();
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT); // we want to hear about new connections
        } catch (IOException e) {
            closeQuietly(serverChannel);
            closeQuietly(selector);
            throw e;
        }

        loopThread = new Thread(this::runLoop, "reddis-event-loop"); // non-daemon: keeps the JVM alive
        running = true;
        loopThread.start();
        System.out.println("Reddis (event loop) listening on port " + getPort());
    }

    public int getPort() {
        return serverChannel.socket().getLocalPort();
    }

    /** Stops the loop, closes every connection and waits for the loop thread to end. */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        selector.wakeup(); // the only Selector method that is safe to call from another thread
        try {
            loopThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("Reddis stopped");
    }

    // ------------------------------------------------------------------ loop

    private void runLoop() {
        long periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMillis);
        long nextRun = System.nanoTime() + periodNanos;
        try {
            while (running) {
                // Sleep until a socket is ready, stop() wakes us, or the periodic task is due.
                long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(nextRun - System.nanoTime()));
                selector.select(waitMillis);
                if (!running) {
                    break;
                }

                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext()) {
                    SelectionKey key = ready.next();
                    ready.remove(); // the selector never clears this set itself: forgetting this re-handles keys forever
                    dispatch(key);
                }

                if (System.nanoTime() - nextRun >= 0) {
                    runPeriodicTask();
                    nextRun = System.nanoTime() + periodNanos;
                }
            }
        } catch (IOException e) {
            System.err.println("Event loop failed: " + e);
        } finally {
            closeEverything();
        }
    }

    private void dispatch(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }
        if (key.isAcceptable()) {
            acceptConnections();
            return;
        }

        Connection connection = (Connection) key.attachment();
        try {
            if (key.isReadable()) {
                onReadable(connection);
            }
            if (key.isValid() && key.isWritable()) {
                onWritable(connection);
            }
        } catch (IOException | CancelledKeyException e) {
            close(connection); // reset by peer, output limit exceeded, ...: only this client is affected
        } catch (RuntimeException e) {
            System.err.println("Unexpected error, closing connection: " + e);
            close(connection);
        }
    }

    // ---------------------------------------------------------------- accept

    private void acceptConnections() {
        try {
            SocketChannel channel;
            while ((channel = serverChannel.accept()) != null) { // non-blocking: null when nobody is waiting
                try {
                    channel.configureBlocking(false);
                    channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    Connection connection = new Connection(channel);
                    connection.key = channel.register(selector, SelectionKey.OP_READ, connection);
                } catch (IOException e) {
                    closeQuietly(channel);
                }
            }
        } catch (IOException e) {
            System.err.println("Accept failed: " + e);
        }
    }

    // ------------------------------------------------------------------ read

    private void onReadable(Connection c) throws IOException {
        int read = c.channel.read(c.in); // appends to the buffer; never blocks
        if (read < 0) {
            c.closeAfterFlush = true;    // the client closed its side: finish sending what we owe it, then close
        } else {
            process(c);
        }
        finishEvent(c);
    }

    /** Decodes and executes every complete command buffered for this client (this is pipelining). */
    private void process(Connection c) throws IOException {
        c.in.flip(); // WRITE mode -> READ mode
        try {
            List<byte[]> command;
            while ((command = decoder.decode(c.in)) != null) { // null = incomplete: wait for more bytes
                if (!command.isEmpty()) {
                    c.writer.write(execute(command));
                }
            }
        } catch (ProtocolException e) {
            c.writer.write(new RespValue.RespError("ERR Protocol error: " + e.getMessage()));
            c.closeAfterFlush = true;
            c.in.clear(); // the stream is garbage from here on: drop it
            return;
        }

        c.in.compact(); // READ mode -> WRITE mode, keeping any half-received command at the front
        if (!c.in.hasRemaining() && !c.growInput()) {
            c.writer.write(new RespValue.RespError("ERR Protocol error: max query buffer length exceeded"));
            c.closeAfterFlush = true;
            c.in.clear();
        }
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

    // ----------------------------------------------------------------- write

    private void onWritable(Connection c) throws IOException {
        finishEvent(c);
    }

    /** Sends what it can, then decides what to listen for next (or closes). */
    private void finishEvent(Connection c) throws IOException {
        flush(c);
        if (c.closeAfterFlush && !c.hasPendingOutput()) {
            close(c);
            return;
        }
        updateInterest(c);
    }

    private void flush(Connection c) throws IOException {
        if (!c.hasPendingOutput()) {
            return;
        }
        c.out.flip();                  // WRITE mode -> READ mode
        try {
            c.channel.write(c.out);    // may send only part of it, even nothing
        } finally {
            c.out.compact();           // keep whatever was NOT sent, back in WRITE mode
        }
    }

    private void updateInterest(Connection c) {
        int ops = 0;
        boolean backlogged = c.out.position() > Connection.OUTPUT_HIGH_WATER;
        if (!c.closeAfterFlush && !backlogged) {
            ops |= SelectionKey.OP_READ;  // backpressure: a client that is not reading replies is not served
        }
        if (c.hasPendingOutput()) {
            ops |= SelectionKey.OP_WRITE; // ONLY while there is something to send (else: busy-spin)
        }
        c.key.interestOps(ops);
    }

    // ------------------------------------------------------------- housekeeping

    private void runPeriodicTask() {
        try {
            periodicTask.run();
        } catch (RuntimeException e) {
            System.err.println("Periodic task failed: " + e);
        }
    }

    private void close(Connection c) {
        c.key.cancel();
        closeQuietly(c.channel);
    }

    private void closeEverything() {
        if (selector != null) {
            for (SelectionKey key : new ArrayList<>(selector.keys())) {
                closeQuietly(key.channel());
            }
            closeQuietly(selector);
        }
        closeQuietly(serverChannel);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do while closing
        }
    }
}