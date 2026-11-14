package com.reddis.replication;

import com.reddis.persistence.AofFormat;
import com.reddis.protocol.ProtocolException;
import com.reddis.protocol.RespDecoder;
import com.reddis.protocol.RespWriter;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The replica's connection to its master: a dedicated thread with BLOCKING I/O that connects, sends PSYNC, receives
 * the snapshot or the missed commands, then reads the live stream. It never touches the data: everything it learns is
 * handed to the event loop through the Replication object. If the connection drops it reconnects by itself.
 */
final class ReplicaLink implements Runnable {

    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 30_000;
    private static final int STREAM_POLL_MILLIS = 1_000; // also the rhythm of the ACKs
    private static final long RECONNECT_DELAY_MILLIS = 1_000;
    private static final int MAX_BUFFER = 600 * 1024 * 1024;

    private final String host;
    private final int port;
    private final Replication replication;
    private final Thread thread;
    private volatile boolean stopped;
    private volatile Socket socket;

    ReplicaLink(String host, int port, Replication replication) {
        this.host = host;
        this.port = port;
        this.replication = replication;
        this.thread = new Thread(this, "reddis-replica-link");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Stops for good: closes the connection (which unblocks any read) and ends the thread. */
    void stop() {
        stopped = true;
        dropConnection();
        thread.interrupt();
    }

    /** Closes the current connection only. The thread notices and reconnects. */
    void dropConnection() {
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                synchronizeAndStream();
            } catch (IOException | RuntimeException e) {
                if (!stopped) {
                    System.err.println("Replication link to " + host + ":" + port + " lost: " + e.getMessage());
                }
            } finally {
                replication.onLinkDown(this);
                dropConnection();
            }
            if (!stopped) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    return; // stop() interrupts us
                }
            }
        }
    }

    private void synchronizeAndStream() throws IOException {
        Socket connection = new Socket();
        socket = connection;
        connection.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        connection.setTcpNoDelay(true);
        connection.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        InputStream in = connection.getInputStream();
        OutputStream out = connection.getOutputStream();

        // Ask for everything after what we already RECEIVED. (Not "applied": commands may still be queued for the
        // loop, and asking for them again would apply them twice.)
        writeCommand(out, "PSYNC", replication.syncReplId(), Long.toString(replication.syncOffset()));

        String reply = readLine(in);
        if (reply.startsWith("+FULLRESYNC ")) {
            String[] parts = reply.split(" ");
            if (parts.length != 3) {
                throw new IOException("malformed FULLRESYNC reply: " + reply);
            }
            byte[] snapshot = readBulk(in);
            replication.onFullSync(this, snapshot, parts[1], Long.parseLong(parts[2]));
        } else if (reply.startsWith("+CONTINUE")) {
            String[] parts = reply.split(" ");
            replication.onContinue(this, parts.length > 1 ? parts[1] : null);
        } else {
            throw new IOException("the master refused PSYNC: " + reply);
        }

        replication.onLinkUp(this);
        connection.setSoTimeout(STREAM_POLL_MILLIS);
        stream(in, out);
    }

    /** Reads the live stream: decode commands, count their bytes, hand them to the loop, ACK once a second. */
    private void stream(InputStream in, OutputStream out) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024); // kept in WRITE mode between reads
        RespDecoder decoder = new RespDecoder();
        long nextAck = System.nanoTime();

        while (!stopped) {
            int read;
            try {
                read = in.read(buffer.array(), buffer.position(), buffer.remaining());
            } catch (SocketTimeoutException quiet) {
                read = 0; // nothing arrived this second: fine, still time to ACK
            }
            if (read < 0) {
                throw new EOFException("the master closed the connection");
            }

            if (read > 0) {
                buffer.position(buffer.position() + read);
                buffer.flip();

                List<List<byte[]>> commands = new ArrayList<>();
                long consumed = 0;
                try {
                    while (true) {
                        int before = buffer.position();
                        List<byte[]> command = decoder.decode(buffer);
                        if (command == null) {
                            break; // the rest is an incomplete command
                        }
                        consumed += buffer.position() - before;
                        if (!command.isEmpty()) {
                            commands.add(command);
                        }
                    }
                } catch (ProtocolException e) {
                    throw new IOException("corrupt replication stream: " + e.getMessage(), e);
                }

                buffer.compact();
                if (!buffer.hasRemaining()) {
                    buffer = grow(buffer); // one command bigger than the buffer
                }
                if (consumed > 0) {
                    replication.onCommands(this, commands, consumed);
                }
            }

            if (System.nanoTime() - nextAck >= 0) {
                writeCommand(out, "REPLCONF", "ACK", Long.toString(replication.ackOffset()));
                nextAck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            }
        }
    }

    private static ByteBuffer grow(ByteBuffer buffer) throws IOException {
        if (buffer.capacity() >= MAX_BUFFER) {
            throw new IOException("a replicated command is larger than " + MAX_BUFFER + " bytes");
        }
        ByteBuffer bigger = ByteBuffer.allocate(Math.min(MAX_BUFFER, buffer.capacity() * 2));
        buffer.flip();
        bigger.put(buffer);
        return bigger;
    }

    // ---------------------------------------------------------- tiny RESP client

    private static void writeCommand(OutputStream out, String... parts) throws IOException {
        List<byte[]> command = new ArrayList<>(parts.length);
        for (String part : parts) {
            command.add(part.getBytes(StandardCharsets.UTF_8));
        }
        RespWriter writer = new RespWriter(out);
        AofFormat.encode(writer, command);
        writer.flush();
    }

    /** Reads one CRLF-terminated line byte by byte (unbuffered, so no stream bytes are swallowed). */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\r') {
            if (b == -1) {
                throw new EOFException("the master closed the connection");
            }
            line.write(b);
        }
        if (in.read() != '\n') {
            throw new IOException("malformed line from the master");
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    /** Reads a bulk string: $len CRLF bytes CRLF. */
    private static byte[] readBulk(InputStream in) throws IOException {
        String header = readLine(in);
        if (!header.startsWith("$")) {
            throw new IOException("expected a bulk string, got: " + header);
        }
        int length = Integer.parseInt(header.substring(1));
        if (length < 0) {
            throw new IOException("the master sent a null snapshot");
        }
        byte[] data = in.readNBytes(length);
        if (data.length < length) {
            throw new EOFException("the snapshot was cut short");
        }
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("malformed end of the snapshot");
        }
        return data;
    }
}