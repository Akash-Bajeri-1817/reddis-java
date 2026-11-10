package com.reddis.server;

import com.reddis.protocol.RespValue;

import java.io.IOException;
import java.util.List;

/** Observer of the event loop: lets other components react to commands without the server knowing about them. */
public interface CommandHooks {

    /** Hooks that do nothing. */
    CommandHooks NONE = new CommandHooks() { };

    /** Called right after a command was executed, before its reply is queued. */
    default void afterCommand(List<byte[]> command, RespValue reply) throws IOException { }

    /** Called once per processed batch of commands, right before the replies are sent. */
    default void beforeReplies() throws IOException { }
}