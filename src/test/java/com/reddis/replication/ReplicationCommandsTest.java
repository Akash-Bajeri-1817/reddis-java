package com.reddis.replication;

import com.reddis.command.CommandArgs;
import com.reddis.command.CommandRouter;
import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.support.RespText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The replication commands on a MASTER (no connection to anyone is ever opened here). */
class ReplicationCommandsTest {

    @TempDir
    Path dir;

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        router = new CommandRouter();
    }

    private void register() {
        ReplicationCommands.register(router, new Replication(new DataStore(), ReplicationConfig.defaults(), dir));
    }

    private String run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        RespValue reply = router.route(command);
        return RespText.render(reply);
    }

    @Test // AC-9
    void replicaofNoOneOnAMasterIsHarmless() {
        register();
        assertEquals("OK", run("REPLICAOF", "NO", "ONE"));
        assertEquals("OK", run("SLAVEOF", "no", "one")); // the old name works too
    }

    @Test
    void replicaofValidatesItsArguments() {
        register();
        assertEquals("-ERR wrong number of arguments for 'replicaof' command", run("REPLICAOF", "host"));
        assertEquals("-ERR Invalid master port", run("REPLICAOF", "host", "abc"));
        assertEquals("-ERR Invalid master port", run("REPLICAOF", "host", "0"));
        assertEquals("-ERR Invalid master port", run("REPLICAOF", "host", "70000"));
    }

    @Test
    void infoReplicationDescribesAMaster() {
        register();
        String info = run("INFO", "replication");
        assertTrue(info.startsWith("# Replication"), info);
        assertTrue(info.contains("role:master"), info);
        assertTrue(info.contains("connected_slaves:0"), info);
        assertTrue(info.contains("master_replid:"), info);
        assertTrue(info.contains("master_repl_offset:0"), info);
    }

    @Test
    void plainInfoKeepsTheOtherSectionsAndAppendsReplication() {
        router.register("INFO", args -> CommandArgs.bulk("# Memory\r\nused_memory:1\r\n"));
        register(); // decorates the INFO registered above

        String all = run("INFO");
        assertTrue(all.contains("used_memory:1"), all);
        assertTrue(all.contains("role:master"), all);

        String onlyReplication = run("INFO", "replication");
        assertFalse(onlyReplication.contains("used_memory"), onlyReplication);
    }

    @Test
    void roleReportsAMasterAndItsOffset() {
        register();
        assertEquals("[master,0]", run("ROLE"));
    }
}