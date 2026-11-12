package com.reddis.session;

import com.reddis.protocol.RespValue;

import java.io.IOException;
import java.util.List;

/** Told about every command the dispatcher actually EXECUTES (not about MULTI/EXEC bookkeeping). */
@FunctionalInterface
public interface CommandObserver {

    CommandObserver NONE = (command, reply) -> { };

    void afterCommand(List<byte[]> command, RespValue reply) throws IOException;
}