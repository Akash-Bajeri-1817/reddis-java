package com.reddis.eviction;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCommandsTest {

    private MutableClock clock;
    private DataStore store;
    private MemoryGuard guard;
    private CommandRouter router;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_000_000L);
        store = new DataStore(clock, new Random(3));
        router = CommandRegistry.standard(store);
        guard = new MemoryGuard(store, MemoryConfig.defaults(), clock, new Random(3), key -> { });
        router.setGuard(guard);
        MemoryCommands.register(router, guard, store);
    }

    private RespValue run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        return router.route(command);
    }

    private static String text(RespValue reply) {
        RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, reply);
        return bulk.isNull() ? null : new String(bulk.data(), StandardCharsets.UTF_8);
    }

    private static List<String> texts(RespValue reply) {
        RespValue.RespArray array = assertInstanceOf(RespValue.RespArray.class, reply);
        return array.elements().stream().map(MemoryCommandsTest::text).toList();
    }

    private static RespValue ok() {
        return new RespValue.SimpleString("OK");
    }

    @Test // AC-8
    void configSetAndGetMaxmemory() {
        assertEquals(ok(), run("CONFIG", "SET", "maxmemory", "100mb"));
        assertEquals(List.of("maxmemory", "104857600"), texts(run("CONFIG", "GET", "maxmemory")));
        assertEquals(104_857_600L, guard.maxMemory());
    }

    @Test // AC-8
    void configSetAndGetThePolicyAndTheSampleCount() {
        assertEquals(ok(), run("CONFIG", "SET", "maxmemory-policy", "allkeys-lfu"));
        assertEquals(ok(), run("config", "set", "maxmemory-samples", "10"));
        assertEquals(List.of("maxmemory-policy", "allkeys-lfu"), texts(run("CONFIG", "GET", "maxmemory-policy")));
        assertEquals(List.of("maxmemory-samples", "10"), texts(run("CONFIG", "GET", "maxmemory-samples")));
        assertSame(EvictionPolicy.ALLKEYS_LFU, guard.policy());
        assertEquals(10, guard.samples());
    }

    @Test
    void configRejectsBadValuesAndUnknownParameters() {
        assertTrue(((RespValue.RespError) run("CONFIG", "SET", "maxmemory-policy", "nonsense")).message()
                .startsWith("ERR Invalid argument"));
        assertInstanceOf(RespValue.RespError.class, run("CONFIG", "SET", "maxmemory-samples", "0"));
        assertInstanceOf(RespValue.RespError.class, run("CONFIG", "SET", "maxmemory", "lots"));
        assertInstanceOf(RespValue.RespError.class, run("CONFIG", "SET", "colour", "blue"));
        assertTrue(texts(run("CONFIG", "GET", "colour")).isEmpty());
        assertInstanceOf(RespValue.RespError.class, run("CONFIG"));
        assertInstanceOf(RespValue.RespError.class, run("CONFIG", "FLY"));
    }

    @Test
    void infoReportsMemoryAndEvictionFigures() {
        run("SET", "k", "v");
        String info = text(run("INFO"));
        assertTrue(info.contains("used_memory:" + store.usedMemory()), info);
        assertTrue(info.contains("maxmemory_policy:noeviction"), info);
        assertTrue(info.contains("evicted_keys:0"), info);
        assertTrue(info.contains("keys:1"), info);
    }

    @Test
    void memoryUsageReportsTheEstimatedSize() {
        run("SET", "k", "value");
        assertEquals(new RespValue.RespInteger(store.memoryUsage("k".getBytes(StandardCharsets.UTF_8))),
                run("MEMORY", "USAGE", "k"));
        assertNull(text(run("MEMORY", "USAGE", "missing")));
    }

    @Test // AC-9
    void objectIdletimeFollowsTheClockAndIsResetByReads() {
        run("SET", "k", "v");
        clock.advance(5_000);
        assertEquals(new RespValue.RespInteger(5), run("OBJECT", "IDLETIME", "k"));
        assertEquals(new RespValue.RespInteger(5), run("OBJECT", "IDLETIME", "k")); // inspecting does not reset it

        run("GET", "k");
        assertEquals(new RespValue.RespInteger(0), run("OBJECT", "IDLETIME", "k"));
        assertNull(text(run("OBJECT", "IDLETIME", "missing")));
    }

    @Test
    void objectFreqNeedsAnLfuPolicy() {
        run("SET", "k", "v");
        assertTrue(((RespValue.RespError) run("OBJECT", "FREQ", "k")).message().contains("LFU"));

        run("CONFIG", "SET", "maxmemory-policy", "allkeys-lfu");
        run("GET", "k");
        RespValue.RespInteger freq = assertInstanceOf(RespValue.RespInteger.class, run("OBJECT", "FREQ", "k"));
        assertTrue(freq.value() >= 6);
    }

    @Test // AC-3
    void theOomErrorBlocksGrowingCommandsWhenFull() {
        run("CONFIG", "SET", "maxmemory", "1");
        assertEquals(ok(), run("SET", "a", "1"));            // empty store: within the limit, the write is allowed
        RespValue denied = run("SET", "b", "2");              // now over the limit: noeviction rejects
        assertTrue(((RespValue.RespError) denied).message().startsWith("OOM"));

        assertEquals("1", text(run("GET", "a")));             // reads still work
        assertEquals(new RespValue.RespInteger(1), run("DEL", "a")); // so does freeing memory
        assertEquals(ok(), run("SET", "b", "2"));
    }

    @Test // AC-2
    void evictionKeepsTheStoreNearTheLimit() {
        run("CONFIG", "SET", "maxmemory-policy", "allkeys-lru");
        run("CONFIG", "SET", "maxmemory", "2kb");
        for (int i = 0; i < 200; i++) {
            clock.advance(1);
            assertEquals(ok(), run("SET", "key-" + i, "value-" + i));
        }
        // the last write may overshoot by one entry, never more
        assertTrue(store.usedMemory() <= 2048 + 400, "used " + store.usedMemory());
        assertTrue(store.size() < 200);
        assertTrue(guard.evictedKeys() > 0);
    }
}