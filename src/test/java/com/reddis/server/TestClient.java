package com.reddis.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A tiny RESP client for tests: sends a command, returns the reply as a simple String. */
final class TestClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    TestClient(int port) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(5_000); // a broken server fails the test after 5s instead of hanging it
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /**
     * Sends one command and returns the reply:
     * "+OK" -> "OK", ":1" -> "1", "$5 hello" -> "hello", null bulk -> "(nil)", errors -> the raw line.
     */
    String send(String... parts) throws IOException {
        StringBuilder command = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            int length = part.getBytes(StandardCharsets.UTF_8).length;
            command.append('$').append(length).append("\r\n").append(part).append("\r\n");
        }
        out.write(command.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return readReply();
    }

    private String readReply() throws IOException {
        String line = readLine();
        String rest = line.substring(1);
        return switch (line.charAt(0)) {
            case '+', ':' -> rest;
            case '-' -> line;
            case '$' -> readBulk(Integer.parseInt(rest));
            default -> throw new IOException("unexpected reply: " + line);
        };
    }

    private String readBulk(int length) throws IOException {
        if (length < 0) {
            return "(nil)";
        }
        byte[] data = in.readNBytes(length);
        in.readNBytes(2); // trailing \r\n
        return new String(data, StandardCharsets.UTF_8);
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\r') {
            if (b == -1) {
                throw new EOFException("server closed the connection");
            }
            buffer.write(b);
        }
        in.read(); // the '\n'
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}