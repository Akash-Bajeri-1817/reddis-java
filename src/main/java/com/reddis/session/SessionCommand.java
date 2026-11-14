package com.reddis.session;

import java.io.IOException;
import java.util.List;

/** A command that needs the connection's Session (PSYNC and REPLCONF), registered with the Dispatcher. */
@FunctionalInterface
public interface SessionCommand {
    void execute(Session session, List<byte[]> args, ReplySink out) throws IOException;
}