package com.reddis.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReadOnlyGuardTest {

    private boolean replica;
    private boolean applying;

    private final ReadOnlyGuard guard = new ReadOnlyGuard(new ReplicaState() {
        @Override
        public boolean isReplica() {
            return replica;
        }

        @Override
        public boolean isApplying() {
            return applying;
        }
    });

    @Test
    void aMasterAcceptsWrites() {
        replica = false;
        assertNull(guard.beforeExecute("SET"));
        assertNull(guard.beforeExecute("DEL"));
    }

    @Test // AC-3
    void aReplicaRejectsWritesFromClients() {
        replica = true;
        assertNotNull(guard.beforeExecute("SET"));
        assertNotNull(guard.beforeExecute("MSET"));
        assertNotNull(guard.beforeExecute("DEL"));
        assertNotNull(guard.beforeExecute("ZADD"));
    }

    @Test
    void aReplicaStillServesReadsAndOtherCommands() {
        replica = true;
        assertNull(guard.beforeExecute("GET"));
        assertNull(guard.beforeExecute("LRANGE"));
        assertNull(guard.beforeExecute("PUBLISH"));
        assertNull(guard.beforeExecute("INFO"));
    }

    @Test // AC-3
    void theMastersStreamIsAppliedEvenOnAReplica() {
        replica = true;
        applying = true;
        assertNull(guard.beforeExecute("SET"));
    }
}