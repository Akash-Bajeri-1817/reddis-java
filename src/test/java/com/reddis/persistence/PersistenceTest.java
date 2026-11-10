package com.reddis.persistence;

import com.reddis.protocol.RespValue;
import com.reddis.protocol.RespWriter;
import com.reddis.support.MutableClock;
import com.reddis.support.StoreDump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {

    private static final long START = 1_000_000L;

    @TempDir
    Path dir;

    private PersistenceConfig aof(FsyncPolicy policy) {
        return new PersistenceConfig(dir, true, policy, 0);
    }

    private PersistenceConfig snapshotsOnly() {
        return new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 0);
    }

    private PersistenceHarness open(PersistenceConfig config) throws IOException {
        return new PersistenceHarness(config, new MutableClock(START));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String get(PersistenceHarness h, String key) throws IOException {
        return PersistenceHarness.text(h.run("GET", key));
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for a condition");
            }
            Thread.sleep(20);
        }
    }

    private static byte[] record(String... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AofFormat.encode(new RespWriter(bytes), java.util.Arrays.stream(parts).map(PersistenceTest::b).toList());
        return bytes.toByteArray();
    }

    // -------------------------------------------------------------- snapshots

    @Test
    void aSnapshotRestoresTheDataAfterARestart() throws Exception {
        try (PersistenceHarness first = open(snapshotsOnly())) {
            first.run("SET", "a", "1");
            first.run("RPUSH", "l", "x", "y");
            first.persistence.save();
        }
        try (PersistenceHarness second = open(snapshotsOnly())) {
            assertEquals("1", get(second, "a"));
            assertEquals(1, second.store.size() - 1); // a and l
        }
    }

    @Test // AC-10
    void aBackgroundSaveWritesTheSnapshotOnAnotherThread() throws Exception {
        try (PersistenceHarness first = open(snapshotsOnly())) {
            first.run("SET", "a", "1");
            assertTrue(first.persistence.startBackgroundSave());
            waitUntil(() -> !first.persistence.isBackgroundSaveRunning());
            assertEquals(0, first.persistence.unsavedChanges());
        }
        try (PersistenceHarness second = open(snapshotsOnly())) {
            assertEquals("1", get(second, "a"));
        }
    }

    @Test
    void onlySuccessfulWritesMakeTheDatasetDirty() throws Exception {
        try (PersistenceHarness h = open(snapshotsOnly())) {
            h.run("GET", "a");
            assertEquals(0, h.persistence.unsavedChanges());
            h.run("SET", "a", "1");
            h.run("SET", "b", "2");
            assertEquals(2, h.persistence.unsavedChanges());
            h.run("SET", "oops"); // wrong number of arguments: failed, so not counted
            assertEquals(2, h.persistence.unsavedChanges());
            h.persistence.save();
            assertEquals(0, h.persistence.unsavedChanges());
        }
    }

    @Test
    void anAutomaticSnapshotIsTakenOnceTheIntervalHasPassed() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 1);
        try (PersistenceHarness h = open(config)) {
            h.run("SET", "a", "1");
            waitUntil(() -> {
                h.persistence.cron(); // the event loop would call this every 100 ms
                return Files.exists(config.rdbPath());
            });
            waitUntil(() -> !h.persistence.isBackgroundSaveRunning());
        }
    }

    @Test
    void closingTakesAFinalSnapshotWhenAutomaticSnapshotsAreOn() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, false, FsyncPolicy.EVERYSEC, 3_600);
        try (PersistenceHarness h = open(config)) {
            h.run("SET", "a", "1");
        }
        assertTrue(Files.exists(config.rdbPath()));
        try (PersistenceHarness again = open(config)) {
            assertEquals("1", get(again, "a"));
        }
    }

    // -------------------------------------------------------------------- AOF

    @Test // AC-6, AC-11 (without sockets)
    void writesAreRecoveredFromTheAofAfterARestart() throws Exception {
        Map<String, String> before;
        try (PersistenceHarness first = open(aof(FsyncPolicy.ALWAYS))) {
            first.run("SET", "a", "1");
            first.run("MSET", "b", "2", "c", "3");
            first.run("DEL", "a");
            first.run("RPUSH", "l", "x", "y", "z");
            first.run("LPOP", "l");
            first.run("HSET", "h", "f", "v");
            first.run("SADD", "s", "m1", "m2");
            first.run("ZADD", "z", "1.5", "x", "-2", "y");
            first.run("ZINCRBY", "z", "10", "y");
            before = StoreDump.of(first.store);
        }
        try (PersistenceHarness second = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals(before, StoreDump.of(second.store));
        }
    }

    @Test // AC-5: the payoff of absolute timestamps
    void relativeTimeoutsSurviveARestartAsAbsoluteDeadlines() throws Exception {
        try (PersistenceHarness first = new PersistenceHarness(aof(FsyncPolicy.ALWAYS), new MutableClock(START))) {
            first.run("SET", "session", "abc", "EX", "100"); // expires at START + 100 000
        }

        // 60 seconds later the server restarts: 40 seconds must remain, not a fresh 100.
        try (PersistenceHarness second = new PersistenceHarness(aof(FsyncPolicy.ALWAYS),
                new MutableClock(START + 60_000))) {
            assertEquals(40_000, second.store.ttlMillis(b("session")));
        }

        // After the deadline the key must be gone.
        try (PersistenceHarness third = new PersistenceHarness(aof(FsyncPolicy.ALWAYS),
                new MutableClock(START + 200_000))) {
            assertNull(get(third, "session"));
            assertEquals(0, third.store.size());
        }
    }

    @Test // AC-8
    void rewritingShrinksTheLogAndAppendingContinuesAfterwards() throws Exception {
        Path log = aof(FsyncPolicy.ALWAYS).aofPath();
        try (PersistenceHarness first = open(aof(FsyncPolicy.ALWAYS))) {
            for (int i = 0; i < 500; i++) {
                first.run("SET", "k", "value-" + i); // 500 commands, but only one key
            }
            long before = Files.size(log);
            first.persistence.rewriteAof();
            long after = Files.size(log);
            assertTrue(after < before / 10, "before=" + before + " after=" + after);

            first.run("SET", "extra", "1"); // appended to the NEW log
        }
        try (PersistenceHarness second = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals("value-499", get(second, "k"));
            assertEquals("1", get(second, "extra"));
        }
    }

    @Test // AC-7
    void aTornTailIsRepairedAndLoggingContinues() throws Exception {
        Path log = aof(FsyncPolicy.ALWAYS).aofPath();
        try (PersistenceHarness first = open(aof(FsyncPolicy.ALWAYS))) {
            first.run("SET", "a", "1");
            first.run("SET", "b", "2");
        }
        long goodLength = Files.size(log);
        // simulate a power cut in the middle of appending "SET c 3"
        Files.write(log, b("*3\r\n$3\r\nSET\r\n$1\r\nc\r\n$1"), StandardOpenOption.APPEND);

        try (PersistenceHarness second = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals("1", get(second, "a"));
            assertEquals("2", get(second, "b"));
            assertNull(get(second, "c"));
            assertEquals(goodLength, Files.size(log)); // the torn bytes were cut off
            second.run("SET", "d", "4");
        }
        try (PersistenceHarness third = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals("4", get(third, "d"));
            assertNull(get(third, "c"));
        }
    }

    @Test // AC-7
    void aCorruptedLogRefusesToStart() throws Exception {
        Files.write(aof(FsyncPolicy.ALWAYS).aofPath(), b("*1\r\n$4\r\nPING\r\n!!garbage\r\n"));
        assertThrows(IOException.class, () -> open(aof(FsyncPolicy.ALWAYS)));
    }

    // ------------------------------------------------------- startup precedence

    @Test // AC-9
    void enablingTheAofOnAnExistingSnapshotBootstrapsTheLog() throws Exception {
        try (PersistenceHarness first = open(snapshotsOnly())) {
            first.run("SET", "a", "1");
            first.persistence.save();
        }
        try (PersistenceHarness second = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals("1", get(second, "a")); // came from the snapshot
        }
        Files.delete(snapshotsOnly().rdbPath());  // now only the AOF can supply the data
        try (PersistenceHarness third = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals("1", get(third, "a"));
        }
    }

    @Test // AC-9
    void theAofTakesPrecedenceOverTheSnapshot() throws Exception {
        try (PersistenceHarness first = open(snapshotsOnly())) {
            first.run("SET", "a", "old");
            first.persistence.save();
        }
        Files.write(aof(FsyncPolicy.ALWAYS).aofPath(), record("SET", "a", "new"));

        try (PersistenceHarness second = open(aof(FsyncPolicy.ALWAYS))) {
            assertEquals("new", get(second, "a"));
        }
    }

    @Test
    void commandsWithoutTheAofEnabledFailCleanly() throws Exception {
        try (PersistenceHarness h = open(snapshotsOnly())) {
            RespValue reply = h.run("REWRITEAOF");
            assertEquals(new RespValue.RespError("ERR AOF is not enabled"), reply);
            assertEquals(new RespValue.SimpleString("OK"), h.run("SAVE"));
        }
    }
}