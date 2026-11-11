package com.reddis.storage;

import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** LRU time and the LFU counter: what is updated, when, and what is NOT. */
class EntryMetadataTest {

    private static final long START = 1_000_000L;

    private MutableClock clock;
    private DataStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        store = new DataStore(clock, new Random(1));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test // AC-9
    void readsUpdateTheLastAccessTimeButInspectionsDoNot() {
        store.set(b("k"), b("v"));
        clock.advance(5_000);

        store.ttlMillis(b("k"));
        store.type(b("k"));
        store.expireAtMillis(b("k"));
        store.memoryUsage(b("k"));
        store.inspect(b("k"));
        assertEquals(START, store.inspect(b("k")).lastAccessMillis());

        store.get(b("k"));
        assertEquals(START + 5_000, store.inspect(b("k")).lastAccessMillis());
    }

    @Test
    void theFrequencyStartsAtFiveAndGrowsWithAccess() {
        store.set(b("k"), b("v"));
        assertEquals(5, store.inspect(b("k")).frequency());

        store.get(b("k")); // from the initial value the first increment always happens (probability 1)
        assertTrue(store.inspect(b("k")).frequency() >= 6);

        for (int i = 0; i < 1_000; i++) {
            store.get(b("k"));
        }
        assertTrue(store.inspect(b("k")).frequency() > 10); // logarithmic: 1000 hits is nowhere near 1000
        assertTrue(store.inspect(b("k")).frequency() < 100);
    }

    @Test
    void theFrequencyDecaysByOnePerIdleMinute() {
        store.set(b("k"), b("v"));
        for (int i = 0; i < 1_000; i++) {
            store.get(b("k"));
        }
        int hot = store.inspect(b("k")).frequency();

        clock.advance(5 * 60_000); // five idle minutes
        assertEquals(Math.max(0, hot - 5), store.inspect(b("k")).frequency());

        clock.advance(24 * 3_600_000L); // a day later
        assertEquals(0, store.inspect(b("k")).frequency());
    }

    @Test
    void accessHistorySurvivesTimeoutChanges() {
        store.set(b("k"), b("v"));
        clock.advance(1_000);
        for (int i = 0; i < 20; i++) {
            store.get(b("k"));
        }
        KeySample before = store.inspect(b("k"));

        store.expire(b("k"), 60_000);
        store.persist(b("k"));

        KeySample after = store.inspect(b("k"));
        assertEquals(before.lastAccessMillis(), after.lastAccessMillis());
        assertEquals(before.frequency(), after.frequency());
    }

    @Test
    void usingACollectionCountsAsAnAccess() {
        store.access(b("list"), ListValue.class, ListValue::new, list -> {
            list.pushRight(b("x"));
            return null;
        });
        clock.advance(3_000);
        store.access(b("list"), ListValue.class, ListValue::new, ListValue::size);
        assertEquals(START + 3_000, store.inspect(b("list")).lastAccessMillis());
    }
}