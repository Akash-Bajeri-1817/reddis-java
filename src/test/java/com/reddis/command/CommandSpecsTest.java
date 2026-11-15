package com.reddis.command;

import com.reddis.storage.DataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandSpecsTest {

    private static List<byte[]> args(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private static List<String> keys(String name, String... args) {
        return CommandSpecs.modifiedKeys(name, args(args)).stream()
                .map(k -> new String(k, StandardCharsets.UTF_8)).toList();
    }

    @Test
    void writesAndMemoryGrowthAreDeclared() {
        assertTrue(CommandSpecs.isWrite("SET"));
        assertFalse(CommandSpecs.isWrite("GET"));
        assertTrue(CommandSpecs.growsMemory("INCR"));
        assertTrue(CommandSpecs.growsMemory("APPEND"));
        assertFalse(CommandSpecs.growsMemory("DEL"));
        assertFalse(CommandSpecs.growsMemory("RENAME"));
        assertFalse(CommandSpecs.growsMemory("FLUSHALL"));
    }

    @Test
    void customTranslationsAreNotVerbatim() {
        assertTrue(CommandSpecs.propagatesVerbatim("LPUSH"));
        assertTrue(CommandSpecs.propagatesVerbatim("INCRBY"));
        assertFalse(CommandSpecs.propagatesVerbatim("SET"));    // EX/PX/NX/XX are rewritten
        assertFalse(CommandSpecs.propagatesVerbatim("EXPIRE")); // becomes PEXPIREAT
        assertFalse(CommandSpecs.propagatesVerbatim("GET"));
    }

    @Test
    void modifiedKeysFollowTheDeclaredPositions() {
        assertEquals(List.of("a", "b"), keys("RENAME", "a", "b"));
        assertEquals(List.of("a", "c"), keys("MSET", "a", "1", "c", "2"));
        assertEquals(List.of("a", "b", "c"), keys("DEL", "a", "b", "c"));
        assertEquals(List.of("k"), keys("INCR", "k"));
        assertTrue(keys("FLUSHALL").isEmpty());
        assertTrue(keys("GET", "k").isEmpty());
    }

    @Test // AC-7: a typo in the table (or a forgotten command) is caught here
    void everyWriteCommandInTheTableIsRegistered() {
        CommandRouter router = CommandRegistry.standard(new DataStore());
        for (String name : CommandSpecs.writeCommandNames()) {
            assertTrue(router.isRegistered(name), name + " is in CommandSpecs but not registered");
        }
    }
}