package com.reddis.eviction;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-6: which commands count as "growing" now comes from CommandSpecs. */
class MemoryGuardSpecsTest {

    @Test
    void theNewGrowingCommandsAreRefusedWhenMemoryIsFullButTheOthersAreNot() {
        MutableClock clock = new MutableClock(1_000_000L);
        DataStore store = new DataStore(clock);
        for (int i = 0; i < 50; i++) {
            store.set(("key" + i).getBytes(), new byte[100]);
        }
        MemoryGuard guard = new MemoryGuard(store,
                new MemoryConfig(store.usedMemory() / 2, EvictionPolicy.NOEVICTION, 5), clock, new Random(1), key -> { });

        for (String growing : new String[]{"INCR", "DECR", "INCRBY", "DECRBY", "APPEND"}) {
            RespValue reply = guard.beforeExecute(growing);
            assertTrue(reply instanceof RespValue.RespError error && error.message().startsWith("OOM"), growing);
        }
        for (String other : new String[]{"RENAME", "FLUSHALL", "STRLEN", "KEYS", "DEL"}) {
            assertNull(guard.beforeExecute(other), other);
        }
    }
}