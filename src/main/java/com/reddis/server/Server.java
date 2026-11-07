package com.reddis.server;

import com.reddis.command.CommandRouter;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Accepts TCP connections and serves each one on a thread from the given executor.
 * The Server owns the executor: stop() shuts it down.
 */
public class Server {

    private final int port;
    private final CommandRouter router;
    private final ExecutorService clientExecutor;

    /** Every connection currently open, so stop() can close them all. Accessed from many threads. */
    private final Set<Socket> openClients = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** @param port 0 means "let the OS pick a free port" (used by tests); see getPort() */
    public Server(int port, CommandRouter router, ExecutorService clientExecutor) {
        this.port = port;
        this.router = router;
        this.clientExecutor = clientExecutor;
    }

    /** Binds the port and starts accepting connections in the background, then returns. */
    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server already started");
        }
        serverSocket = new ServerSocket(port);
        acceptThread = new Thread(this::acceptLoop, "reddis-acceptor");

        // Volatile write LAST: it publishes the two fields above to any thread that later
        // reads 'running' as true (for example the shutdown hook calling stop()).
        running = true;

        acceptThread.start();
        System.out.println("Reddis listening on port " + getPort());
    }

    /** The port actually bound (useful when constructed with port 0). */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept(); // blocks until a client connects
                client.setTcpNoDelay(true);
                openClients.add(client);
                try {
                    clientExecutor.execute(() -> serve(client)); // hand off, then go back to accept()
                } catch (RejectedExecutionException e) {
                    openClients.remove(client);
                    closeQuietly(client);
                }
            } catch (IOException e) {
                if (!running) {
                    break; // stop() closed the ServerSocket, which makes accept() throw: expected
                }
                System.err.println("Accept failed: " + e);
            }
        }
    }

    private void serve(Socket client) {
        try {
            new ClientHandler(client, router).run(); // closes the socket when finished
        } finally {
            openClients.remove(client);
        }
    }

    /** Stops accepting, disconnects every client and waits for worker threads to finish. */
    public void stop() {
        if (!running) {
            return; // never started, or already stopped
        }
        running = false;
        closeQuietly(serverSocket);          // makes the blocked accept() throw
        try {
            acceptThread.join(2_000);        // after this no new clients can appear
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Handlers are blocked in read(); closing their sockets makes read() throw so they exit.
        openClients.forEach(Server::closeQuietly);

        clientExecutor.shutdown();
        try {
            if (!clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            clientExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Reddis stopped");
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do while shutting down
        }
    }
}