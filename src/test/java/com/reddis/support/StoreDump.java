package com.reddis.support;

import com.reddis.storage.DataStore;
import com.reddis.storage.HashValue;
import com.reddis.storage.ListValue;
import com.reddis.storage.SetValue;
import com.reddis.storage.SortedSetValue;
import com.reddis.storage.StringValue;
import com.reddis.storage.ScoredMember;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Test helpers: describe a whole store as a sorted map (to compare stores) and fill one with every type. */
public final class StoreDump {

    private StoreDump() { }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** ISO-8859-1 maps bytes 1:1 to chars, so even binary data compares exactly. */
    private static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String join(List<byte[]> items) {
        return items.stream().map(StoreDump::str).collect(Collectors.joining(","));
    }

    /** key -> "type:content|expireAt=deadline" for every live entry. */
    public static Map<String, String> of(DataStore store) throws IOException {
        Map<String, String> dump = new TreeMap<>();
        store.forEachEntry((key, value, expireAt) -> {
            String text;
            if (value instanceof StringValue string) {
                text = "string:" + str(string.bytes());
            } else if (value instanceof ListValue list) {
                text = "list:" + join(list.range(0, -1));
            } else if (value instanceof HashValue hash) {
                List<byte[]> flat = hash.flatten();
                List<String> pairs = new ArrayList<>();
                for (int i = 0; i < flat.size(); i += 2) {
                    pairs.add(str(flat.get(i)) + "=" + str(flat.get(i + 1)));
                }
                Collections.sort(pairs);
                text = "hash:" + String.join(",", pairs);
            } else if (value instanceof SetValue set) {
                List<String> members = new ArrayList<>(set.members().stream().map(StoreDump::str).toList());
                Collections.sort(members);
                text = "set:" + String.join(",", members);
            } else if (value instanceof SortedSetValue zset) {
                text = "zset:" + zset.range(0, -1, false).stream()
                        .map((ScoredMember m) -> str(m.member()) + "@" + m.score())
                        .collect(Collectors.joining(","));
            } else {
                text = "unknown:" + value.typeName();
            }
            dump.put(str(key), text + "|expireAt=" + expireAt);
        });
        return dump;
    }

    /** Fills the store with one key of every type, a key with a deadline, and a binary-safe key. */
    public static void populate(DataStore store) {
        store.set(b("str"), b("hello"));
        store.set(b("temp"), b("expiring"), 60_000);
        store.set(new byte[]{0, (byte) 255, '\r', '\n'}, new byte[]{(byte) 200, 0, '\r', '\n'});

        store.access(b("list"), ListValue.class, ListValue::new, list -> {
            for (int i = 0; i < 200; i++) { // more than one AOF chunk (64)
                list.pushRight(b("item-" + i));
            }
            return null;
        });
        store.access(b("hash"), HashValue.class, HashValue::new, hash -> {
            hash.put(b("f1"), b("v1"));
            hash.put(b("f2"), b("v2"));
            return null;
        });
        store.access(b("set"), SetValue.class, SetValue::new, set -> {
            set.add(b("a"));
            set.add(b("b"));
            set.add(b("c"));
            return null;
        });
        store.access(b("zset"), SortedSetValue.class, SortedSetValue::new, zset -> {
            zset.add(b("low"), -1.5);
            zset.add(b("mid"), 0);
            zset.add(b("tie-a"), 2);
            zset.add(b("tie-b"), 2);
            zset.add(b("top"), Double.POSITIVE_INFINITY);
            return null;
        });
    }
}