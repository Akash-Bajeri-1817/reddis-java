package com.reddis.server;

import com.reddis.command.CommandRouter;
import com.reddis.protocol.ProtocolException;
import com.reddis.protocol.RespParser;
import com.reddis.protocol.RespValue;
import com.reddis.protocol.RespWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;

/** Serves ONE client connection: read command -> execute -> write reply, until disconnect. */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final CommandRouter router;

    public ClientHandler(Socket socket, CommandRouter router) {
        this.socket = socket;
        this.router = router;
    }

    @Override
    public void run() {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        System.out.println("Client connected: " + remote);

        // try-with-resources closes the socket automatically, even if an exception occurs.
        try (Socket s = socket) {
            RespParser parser = new RespParser(new BufferedInputStream(s.getInputStream()));
            RespWriter writer = new RespWriter(new BufferedOutputStream(s.getOutputStream()));

            while (true) {
                List<byte[]> command;
                try {
                    command = parser.readCommand();
                } catch (ProtocolException e) {
                    // Client sent garbage: tell them, then drop only this connection.
                    writer.write(new RespValue.RespError("ERR Protocol error: " + e.getMessage()));
                    writer.flush();
                    break;
                }

                if (command == null) {
                    break; // client disconnected cleanly
                }
                if (command.isEmpty()) {
                    continue; // empty array: ignore
                }

                writer.write(execute(command));
                writer.flush(); // without this the reply would sit in our buffer
            }
        } catch (IOException e) {
            System.out.println("Connection error with " + remote + ": " + e);
        } finally {
            System.out.println("Client disconnected: " + remote);
        }
    }

    /** A bug in one command must never kill the connection or the server (NFR-4). */
    private RespValue execute(List<byte[]> command) {
        try {
            return router.route(command);
        } catch (RuntimeException e) {
            e.printStackTrace();
            return new RespValue.RespError("ERR internal error");
        }
    }
}