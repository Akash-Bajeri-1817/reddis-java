package com.reddis.session;

import com.reddis.protocol.RespValue;

import java.io.IOException;

/** Where the replies to the CURRENT command go (the client's output buffer). */
@FunctionalInterface
public interface ReplySink {
    void send(RespValue reply) throws IOException;
}