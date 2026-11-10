package com.reddis.persistence;

import com.reddis.storage.DataStore;
import com.reddis.storage.HashValue;
import com.reddis.storage.ListValue;
import com.reddis.storage.RedisValue;
import com.reddis.storage.ScoredMember;
import com.reddis.storage.SetValue;
import com.reddis.storage.SortedSetValue;
import com.reddis.storage.StringValue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * The snapshot ("RDB") file format, version 1:
 *
 *   "REDDIS" version(1 byte)
 *   repeated:  [0xFE expireAtMillis(8)]?  typeByte  key  value
 *   0xFF  crc32(8)           (the CRC covers every byte before the checksum itself)
 *
 * key and string values are int-length-prefixed byte arrays; collections are int-count-prefixed.
 * Loading is defensive: any length or count larger than the file itself is rejected as corruption.
 */
public final class SnapshotCodec {

    private static final byte[] MAGIC = "REDDIS".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    private static final int OP_STRING = 0;
    private static final int OP_LIST = 1;
    private static final int OP_HASH = 2;
    private static final int OP_SET = 3;
    private static final int OP_ZSET = 4;
    private static final int OP_EXPIRE_MS = 0xFE;
    private static final int OP_EOF = 0xFF;

    private SnapshotCodec() { }

    // ---------------------------------------------------------------- writing

    /** Serializes every live entry into a new byte array: a consistent point-in-time copy. */
    public static byte[] serialize(DataStore store) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        DataOutputStream out = new DataOutputStream(new CheckedOutputStream(bytes, crc));

        out.write(MAGIC);
        out.writeByte(VERSION);
        store.forEachEntry((key, value, expireAt) -> writeEntry(out, key, value, expireAt));
        out.writeByte(OP_EOF);

        long checksum = crc.getValue();                 // taken BEFORE the checksum bytes themselves are written
        new DataOutputStream(bytes).writeLong(checksum); // written to the raw stream, bypassing the CRC
        return bytes.toByteArray();
    }

    private static void writeEntry(DataOutputStream out, byte[] key, RedisValue value, long expireAt)
            throws IOException {
        if (expireAt != DataStore.NO_EXPIRY) {
            out.writeByte(OP_EXPIRE_MS);
            out.writeLong(expireAt);
        }

        if (value instanceof StringValue string) {
            out.writeByte(OP_STRING);
            writeBytes(out, key);
            writeBytes(out, string.bytes());
        } else if (value instanceof ListValue list) {
            out.writeByte(OP_LIST);
            writeBytes(out, key);
            List<byte[]> items = list.range(0, -1);
            out.writeInt(items.size());
            for (byte[] item : items) {
                writeBytes(out, item);
            }
        } else if (value instanceof HashValue hash) {
            out.writeByte(OP_HASH);
            writeBytes(out, key);
            List<byte[]> flat = hash.flatten(); // field, value, field, value, ...
            out.writeInt(flat.size() / 2);
            for (byte[] part : flat) {
                writeBytes(out, part);
            }
        } else if (value instanceof SetValue set) {
            out.writeByte(OP_SET);
            writeBytes(out, key);
            List<byte[]> members = set.members();
            out.writeInt(members.size());
            for (byte[] member : members) {
                writeBytes(out, member);
            }
        } else if (value instanceof SortedSetValue zset) {
            out.writeByte(OP_ZSET);
            writeBytes(out, key);
            List<ScoredMember> items = zset.range(0, -1, false);
            out.writeInt(items.size());
            for (ScoredMember item : items) {
                writeBytes(out, item.member());
                out.writeDouble(item.score());
            }
        } else {
            throw new IOException("cannot serialize a value of type " + value.typeName());
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    // ---------------------------------------------------------------- reading

    /**
     * Loads a snapshot file into the store (expired entries are skipped).
     *
     * @return the number of entries in the file
     * @throws IOException if the file is not a valid, complete, uncorrupted snapshot. The caller must then
     *                     refuse to start rather than run with partial data.
     */
    public static int load(Path file, DataStore store) throws IOException {
        long fileSize = Files.size(file);
        CRC32 crc = new CRC32();

        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            DataInputStream in = new DataInputStream(new CheckedInputStream(raw, crc));

            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("not a Reddis snapshot (bad magic header)");
            }
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }

            long expireAt = DataStore.NO_EXPIRY;
            int loaded = 0;
            while (true) {
                int opcode = in.readUnsignedByte(); // throws EOFException if the file is cut short
                if (opcode == OP_EOF) {
                    break;
                }
                if (opcode == OP_EXPIRE_MS) {
                    expireAt = in.readLong();
                    continue;
                }

                byte[] key = readBytes(in, fileSize);
                RedisValue value = switch (opcode) {
                    case OP_STRING -> new StringValue(readBytes(in, fileSize));
                    case OP_LIST -> readList(in, fileSize);
                    case OP_HASH -> readHash(in, fileSize);
                    case OP_SET -> readSet(in, fileSize);
                    case OP_ZSET -> readSortedSet(in, fileSize);
                    default -> throw new IOException("unknown record type " + opcode);
                };
                store.restore(key, value, expireAt);
                expireAt = DataStore.NO_EXPIRY;
                loaded++;
            }

            long expectedChecksum = crc.getValue(); // everything up to and including the EOF marker
            long storedChecksum = in.readLong();
            if (storedChecksum != expectedChecksum) {
                throw new IOException("checksum mismatch: the snapshot is corrupted");
            }
            if (in.read() != -1) {
                throw new IOException("unexpected data after the checksum");
            }
            return loaded;
        }
    }

    private static ListValue readList(DataInputStream in, long max) throws IOException {
        ListValue list = new ListValue();
        int count = readCount(in, max);
        for (int i = 0; i < count; i++) {
            list.pushRight(readBytes(in, max));
        }
        return list;
    }

    private static HashValue readHash(DataInputStream in, long max) throws IOException {
        HashValue hash = new HashValue();
        int count = readCount(in, max);
        for (int i = 0; i < count; i++) {
            byte[] field = readBytes(in, max);
            hash.put(field, readBytes(in, max));
        }
        return hash;
    }

    private static SetValue readSet(DataInputStream in, long max) throws IOException {
        SetValue set = new SetValue();
        int count = readCount(in, max);
        for (int i = 0; i < count; i++) {
            set.add(readBytes(in, max));
        }
        return set;
    }

    private static SortedSetValue readSortedSet(DataInputStream in, long max) throws IOException {
        SortedSetValue zset = new SortedSetValue();
        int count = readCount(in, max);
        for (int i = 0; i < count; i++) {
            byte[] member = readBytes(in, max);
            double score = in.readDouble();
            if (Double.isNaN(score)) {
                throw new IOException("corrupt snapshot: NaN score");
            }
            zset.add(member, score);
        }
        return zset;
    }

    /** No single item can be longer than the whole file: anything bigger is corruption, not a reason to allocate. */
    private static byte[] readBytes(DataInputStream in, long max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) {
            throw new IOException("corrupt snapshot: invalid length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static int readCount(DataInputStream in, long max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) {
            throw new IOException("corrupt snapshot: invalid element count " + count);
        }
        return count;
    }
}