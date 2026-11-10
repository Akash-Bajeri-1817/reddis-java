package com.reddis.persistence;

import com.reddis.command.CommandArgs;
import com.reddis.protocol.RespValue;
import com.reddis.protocol.RespWriter;
import com.reddis.storage.DataStore;
import com.reddis.storage.HashValue;
import com.reddis.storage.ListValue;
import com.reddis.storage.RedisValue;
import com.reddis.storage.ScoredMember;
import com.reddis.storage.SetValue;
import com.reddis.storage.SortedSetValue;
import com.reddis.storage.StringValue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encoding of AOF records (plain RESP command arrays) and generation of a minimal log for a dataset. */
public final class AofFormat {

    /** Elements per generated command, so one huge collection does not become one huge command. */
    private static final int CHUNK = 64;

    private AofFormat() { }

    public static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** Writes one command as a RESP array of bulk strings. */
    public static void encode(RespWriter writer, List<byte[]> parts) throws IOException {
        List<RespValue> elements = new ArrayList<>(parts.size());
        for (byte[] part : parts) {
            elements.add(new RespValue.BulkString(part));
        }
        writer.write(new RespValue.RespArray(elements));
    }

    /**
     * Writes the MINIMAL commands that rebuild the store's current dataset: one SET, or a few RPUSH / HSET /
     * SADD / ZADD per key, then PEXPIREAT (absolute) for keys with a deadline.
     */
    public static void writeDataset(DataStore store, OutputStream out) throws IOException {
        RespWriter writer = new RespWriter(out);
        store.forEachEntry((key, value, expireAt) -> {
            writeValue(writer, key, value);
            if (expireAt != DataStore.NO_EXPIRY) {
                encode(writer, List.of(ascii("PEXPIREAT"), key, ascii(Long.toString(expireAt))));
            }
        });
        writer.flush();
    }

    private static void writeValue(RespWriter writer, byte[] key, RedisValue value) throws IOException {
        if (value instanceof StringValue string) {
            encode(writer, List.of(ascii("SET"), key, string.bytes()));
        } else if (value instanceof ListValue list) {
            writeChunked(writer, "RPUSH", key, list.range(0, -1), 1);
        } else if (value instanceof HashValue hash) {
            writeChunked(writer, "HSET", key, hash.flatten(), 2);   // field, value pairs
        } else if (value instanceof SetValue set) {
            writeChunked(writer, "SADD", key, set.members(), 1);
        } else if (value instanceof SortedSetValue zset) {
            List<byte[]> flat = new ArrayList<>();                   // score, member pairs
            for (ScoredMember item : zset.range(0, -1, false)) {
                flat.add(ascii(CommandArgs.formatScore(item.score())));
                flat.add(item.member());
            }
            writeChunked(writer, "ZADD", key, flat, 2);
        } else {
            throw new IOException("cannot rewrite a value of type " + value.typeName());
        }
    }

    /** One command per CHUNK groups of elements; 'group' keeps field/value (or score/member) pairs together. */
    private static void writeChunked(RespWriter writer, String command, byte[] key, List<byte[]> items, int group)
            throws IOException {
        int step = CHUNK * group;
        for (int from = 0; from < items.size(); from += step) {
            List<byte[]> parts = new ArrayList<>();
            parts.add(ascii(command));
            parts.add(key);
            parts.addAll(items.subList(from, Math.min(from + step, items.size())));
            encode(writer, parts);
        }
    }
}