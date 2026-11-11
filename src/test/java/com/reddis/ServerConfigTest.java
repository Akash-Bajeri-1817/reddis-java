package com.reddis;

import com.reddis.eviction.EvictionPolicy;
import com.reddis.persistence.FsyncPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void defaults() {
        ServerConfig config = ServerConfig.parse();
        assertEquals(6379, config.port());
        assertFalse(config.blocking());
        assertEquals(Path.of("data"), config.persistence().dir());
        assertFalse(config.persistence().appendOnly());
        assertSame(FsyncPolicy.EVERYSEC, config.persistence().fsync());
        assertEquals(0, config.persistence().saveIntervalSeconds());
        assertEquals(0, config.memory().maxMemoryBytes());
        assertSame(EvictionPolicy.NOEVICTION, config.memory().policy());
        assertEquals(5, config.memory().samples());
    }

    @Test
    void parsesThePortAndTheLegacyFlag() {
        ServerConfig config = ServerConfig.parse("6380", "--blocking");
        assertEquals(6380, config.port());
        assertTrue(config.blocking());
    }

    @Test
    void parsesPersistenceOptions() {
        ServerConfig config = ServerConfig.parse("--dir", "somewhere", "--appendonly", "yes",
                "--appendfsync", "always", "--save-interval", "30");
        assertEquals(Path.of("somewhere"), config.persistence().dir());
        assertTrue(config.persistence().appendOnly());
        assertSame(FsyncPolicy.ALWAYS, config.persistence().fsync());
        assertEquals(30, config.persistence().saveIntervalSeconds());
    }

    @Test
    void parsesMemoryOptions() {
        ServerConfig config = ServerConfig.parse("--maxmemory", "100mb", "--maxmemory-policy", "allkeys-lfu",
                "--maxmemory-samples", "10");
        assertEquals(100L * 1024 * 1024, config.memory().maxMemoryBytes());
        assertSame(EvictionPolicy.ALLKEYS_LFU, config.memory().policy());
        assertEquals(10, config.memory().samples());
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--colour", "blue"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--dir"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--appendonly", "maybe"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--maxmemory-policy", "nonsense"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--maxmemory", "lots"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--maxmemory-samples", "0"));
    }
}