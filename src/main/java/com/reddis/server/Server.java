package com.reddis.server;

import com.reddis.command.CommandRouter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/** Accepts TCP connections and hands each one to a ClientHandler. */
public class Server {

    private final int port;
    private final CommandRouter router;

    public Server(int port, CommandRouter router) {
        this.port = port;
        this.router = router;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Reddis listening on port " + port);

            while (true) {
                Socket client = serverSocket.accept(); // blocks until a client connects
                client.setTcpNoDelay(true);            // disable Nagle's algorithm: lower latency

                // KNOWN LIMITATION (fixed in Module 3): we serve the client on THIS thread,
                // so no other client can be accepted until this one disconnects.
                new ClientHandler(client, router).run();
            }
        }
    }
}