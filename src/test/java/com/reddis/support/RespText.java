package com.reddis.support;

import com.reddis.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Test helper: renders a reply as compact text so assertions stay readable, e.g. "[OK,1]" or "-ERR ...". */
public final class RespText {

    private RespText() { }

    public static String render(RespValue value) {
        return switch (value) {
            case RespValue.SimpleString s -> s.value();
            case RespValue.RespError e -> "-" + e.message();
            case RespValue.RespInteger i -> Long.toString(i.value());
            case RespValue.BulkString b -> b.isNull() ? "(nil)" : new String(b.data(), StandardCharsets.UTF_8);
            case RespValue.RespArray a -> a.isNull()
                    ? "(nil)"
                    : "[" + a.elements().stream().map(RespText::render).collect(Collectors.joining(",")) + "]";
        };
    }
}