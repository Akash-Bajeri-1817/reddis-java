package com.reddis.protocol;

import java.io.IOException;

/** Thrown when the client sends bytes that violate RESP. */
public class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}