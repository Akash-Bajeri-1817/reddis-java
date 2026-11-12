package com.reddis.session;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchRegistryTest {

    private final WatchRegistry registry = new WatchRegistry();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Session session() {
        return new Session(message -> { });
    }

    @Test
    void touchingAKeyMarksOnlyItsWatchersDirty() {
        Session watcher = session();
        Session bystander = session();
        registry.watch(watcher, b("k"));
        registry.watch(bystander, b("other"));

        registry.touch(b("k"));

        assertTrue(watcher.watchDirty);
        assertFalse(bystander.watchDirty);
    }

    @Test
    void touchingAnUnwatchedKeyChangesNothing() {
        Session watcher = session();
        registry.watch(watcher, b("k"));
        registry.touch(b("somethingElse"));
        assertFalse(watcher.watchDirty);
    }

    @Test
    void unwatchAllClearsTheWatchesAndTheDirtyFlag() {
        Session watcher = session();
        registry.watch(watcher, b("a"));
        registry.watch(watcher, b("b"));
        registry.touch(b("a"));

        registry.unwatchAll(watcher);

        assertFalse(watcher.watchDirty);
        assertEquals(0, registry.watchedKeyCount());
        registry.touch(b("a")); // no longer watched
        assertFalse(watcher.watchDirty);
    }

    @Test
    void watchingTheSameKeyTwiceCountsOnce() {
        Session watcher = session();
        registry.watch(watcher, b("k"));
        registry.watch(watcher, b("k"));
        assertEquals(1, registry.watchedKeyCount());
    }
}