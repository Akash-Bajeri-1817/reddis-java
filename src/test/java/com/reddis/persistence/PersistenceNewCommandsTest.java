package com.reddis.persistence;

import com.reddis.support.MutableClock;
import com.reddis.support.StoreDump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersistenceNewCommandsTest {

    @TempDir
    Path dir;

    @Test // AC-5
    void theNewCommandsSurviveAnAofReplay() throws Exception {
        PersistenceConfig config = new PersistenceConfig(dir, true, FsyncPolicy.ALWAYS, 0);
        Map<String, String> before;

        try (PersistenceHarness first = new PersistenceHarness(config, new MutableClock(1_000_000L))) {
            first.run("SET", "n", "5");
            first.run("INCR", "n");
            first.run("INCRBY", "n", "10");
            first.run("APPEND", "s", "ab");
            first.run("APPEND", "s", "cd");
            first.run("SET", "lock", "id", "NX");
            first.run("SET", "lock", "other", "NX");   // fails: must not be logged
            first.run("RENAME", "s", "t");
            first.run("SET", "gone", "1");
            first.run("FLUSHALL");                      // everything above disappears
            first.run("SET", "after", "1");
            first.run("INCR", "counter");
            first.run("SET", "keep", "v");
            first.run("RENAME", "keep", "kept");
            before = StoreDump.of(first.store);
        }

        try (PersistenceHarness second = new PersistenceHarness(config, new MutableClock(1_000_000L))) {
            assertEquals(before, StoreDump.of(second.store));
            assertEquals("1", PersistenceHarness.text(second.run("GET", "after")));
            assertEquals("1", PersistenceHarness.text(second.run("GET", "counter")));
            assertEquals("v", PersistenceHarness.text(second.run("GET", "kept")));
            assertNull(PersistenceHarness.text(second.run("GET", "lock"))); // flushed
        }
    }
}