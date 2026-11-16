package com.reddis.command;

import com.reddis.support.CommandHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityCommandsTest {

    private final CommandHarness h = new CommandHarness();

    @Test
    void helloWithoutArgumentsOrWithProtocolTwoDescribesTheServer() {
        String expected = "[server,reddis,version,1.0.0,proto,2,id,1,mode,standalone,role,master,modules,[]]";
        assertEquals(expected, h.run("HELLO"));
        assertEquals(expected, h.run("HELLO", "2"));
    }

    @Test // clients that try RESP3 first fall back to RESP2 when they see NOPROTO
    void helloThreeIsRefusedWithNoproto() {
        assertEquals("-NOPROTO unsupported protocol version", h.run("HELLO", "3"));
        assertEquals("-ERR Protocol version is not an integer or out of range", h.run("HELLO", "abc"));
    }

    @Test
    void clientHousekeepingCommandsAreAccepted() {
        assertEquals("OK", h.run("CLIENT", "SETNAME", "my-app"));
        assertEquals("OK", h.run("CLIENT", "SETINFO", "LIB-NAME", "lettuce"));
        assertEquals("(nil)", h.run("CLIENT", "GETNAME"));
        assertEquals("-ERR unknown subcommand 'fly' for 'client'", h.run("CLIENT", "FLY"));
        assertEquals("-ERR wrong number of arguments for 'client' command", h.run("CLIENT"));
    }

    @Test
    void onlyDatabaseZeroExists() {
        assertEquals("OK", h.run("SELECT", "0"));
        assertEquals("-ERR DB index is out of range", h.run("SELECT", "1"));
        assertEquals("-ERR value is not an integer or out of range", h.run("SELECT", "x"));
    }

    @Test
    void commandReturnsAnEmptyArray() {
        assertEquals("[]", h.run("COMMAND"));
        assertEquals("[]", h.run("COMMAND", "DOCS"));
    }

    @Test
    void timeReturnsSecondsAndMicroseconds() {
        String reply = h.run("TIME");
        String[] parts = reply.substring(1, reply.length() - 1).split(",");
        assertEquals(2, parts.length);
        assertTrue(Long.parseLong(parts[0]) > 1_600_000_000L);
        long micros = Long.parseLong(parts[1]);
        assertTrue(micros >= 0 && micros < 1_000_000);
    }
}