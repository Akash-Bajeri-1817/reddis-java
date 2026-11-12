package com.reddis.session;

import com.reddis.protocol.RespValue;

/** Where messages PUSHED to a subscriber go. The implementation deals with delivery failures itself. */
@FunctionalInterface
public interface MessageSink {
    void send(RespValue message);
}