package com.reddis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigReplicationTest {

    @Test
    void aServerIsAMasterByDefault() {
        ServerConfig config = ServerConfig.parse();
        assertFalse(config.replication().isReplica());
        assertEquals(1024 * 1024, config.replication().backlogBytes());
    }

    @Test
    void parsesReplicaofAndTheBacklogSize() {
        ServerConfig config = ServerConfig.parse("--replicaof", "10.0.0.5", "6380", "--repl-backlog-size", "2mb");
        assertTrue(config.replication().isReplica());
        assertEquals("10.0.0.5", config.replication().masterHost());
        assertEquals(6380, config.replication().masterPort());
        assertEquals(2L * 1024 * 1024, config.replication().backlogBytes());
    }

    @Test
    void rejectsIncompleteOrInvalidOptions() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--replicaof", "host"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--replicaof", "host", "notaport"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--replicaof", "host", "70000"));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse("--repl-backlog-size", "10b"));
    }
}