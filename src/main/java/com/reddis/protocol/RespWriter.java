package com.reddis.protocol;

import com.reddis.protocol.RespValue.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encodes RespValue objects into RESP bytes.
 * Does NOT flush automatically: call flush() once a full reply is written.
 */
public class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out;
    }

    public void write(RespValue value) throws IOException {
        // Pattern-matching switch: the compiler verifies all 5 types are covered.
        switch (value) {
            case SimpleString s -> writeLine('+', s.value());
            case RespError e -> writeLine('-', e.message());
            case RespInteger i -> writeLine(':', Long.toString(i.value()));
            case BulkString b -> writeBulk(b);
            case RespArray a -> writeArray(a);
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    private void writeBulk(BulkString bulk) throws IOException {
        if (bulk.isNull()) {
            writeLine('$', "-1");
            return;
        }
        writeLine('$', Integer.toString(bulk.data().length));
        out.write(bulk.data());
        out.write(CRLF);
    }

    private void writeArray(RespArray array) throws IOException {
        writeLine('*', Integer.toString(array.elements().size()));
        for (RespValue element : array.elements()) {
            write(element);
        }
    }

    /**
     * Line-style types must not contain CR or LF, otherwise a client could smuggle
     * extra "replies" into the stream (protocol injection). We replace them with spaces.
     */
    private void writeLine(char prefix, String text) throws IOException {
        out.write(prefix);
        out.write(text.replace('\r', ' ').replace('\n', ' ').getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}