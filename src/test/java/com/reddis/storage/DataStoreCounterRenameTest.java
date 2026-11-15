package com.reddis.storage;

import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataStoreCounterRenameTest {

    private MutableClock clock;
    private DataStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        store = new DataStore(clock, new Random(1));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void incrementByKeepsTheMemoryAccountingExact() {
        store.incrementBy(b("k"), 5);
        assertEquals(store.memoryUsage(b("k")), store.usedMemory());
        long small = store.usedMemory();

        store.incrementBy(b("k"), 999_999_999_999L); // a longer string needs more memory
        assertTrue(store.usedMemory() > small);
        store.validate();
    }

    @Test
    void aRenamedKeyStillExpiresAtItsOriginalDeadline() {
        store.set(b("a"), b("v"), 100);
        assertTrue(store.rename(b("a"), b("b")));

        clock.advance(100);
        assertEquals(1, store.purgeExpired()); // the active purge knows the NEW name
        assertNull(store.get(b("b")));
        store.validate();
    }

    @Test
    void theBookkeepingSurvivesRandomCountersAppendsAndRenames() {
        Random random = new Random(77);
        for (int step = 1; step <= 3_000; step++) {
            byte[] a = b("k" + random.nextInt(12));
            byte[] c = b("k" + random.nextInt(12));
            try {
                switch (random.nextInt(8)) {
                    case 0 -> store.set(a, b(Integer.toString(random.nextInt(1000))));
                    case 1 -> store.set(a, b("5"), 1 + random.nextInt(200));
                    case 2 -> store.incrementBy(a, random.nextInt(21) - 10);
                    case 3 -> store.append(a, b("x".repeat(random.nextInt(30))));
                    case 4 -> store.rename(a, c);
                    case 5 -> store.delete(a);
                    case 6 -> store.access(a, ListValue.class, ListValue::new, list -> {
                        list.pushRight(b("e"));
                        return null;
                    });
                    default -> {
                        clock.advance(random.nextInt(80));
                        store.purgeExpired();
                    }
                }
            } catch (WrongTypeException | NotAnIntegerException ignored) {
                // the random key held something else: expected, nothing changed
            }
            if (step % 100 == 0) {
                store.validate();
            }
        }
        store.validate();
    }
}