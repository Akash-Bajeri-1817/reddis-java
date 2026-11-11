package com.reddis.eviction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryConfigTest {

    @Test
    void parsesSizesWithBinarySuffixes() {
        assertEquals(1024, MemoryConfig.parseBytes("1024"));
        assertEquals(100, MemoryConfig.parseBytes("100b"));
        assertEquals(1024, MemoryConfig.parseBytes("1kb"));
        assertEquals(2L * 1024 * 1024, MemoryConfig.parseBytes("2MB"));
        assertEquals(1L << 30, MemoryConfig.parseBytes(" 1gb "));
        assertEquals(0, MemoryConfig.parseBytes("0"));
    }

    @Test
    void rejectsInvalidSizes() {
        for (String bad : new String[]{"", "abc", "-1", "12xb", "1.5mb", "kb"}) {
            assertThrows(IllegalArgumentException.class, () -> MemoryConfig.parseBytes(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> MemoryConfig.parseBytes("99999999999gb")); // overflow
    }

    @Test
    void validatesTheConfigurationValues() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryConfig(-1, EvictionPolicy.NOEVICTION, 5));
        assertThrows(IllegalArgumentException.class, () -> new MemoryConfig(0, EvictionPolicy.NOEVICTION, 0));
        assertEquals(0, MemoryConfig.defaults().maxMemoryBytes());
        assertSame(EvictionPolicy.NOEVICTION, MemoryConfig.defaults().policy());
    }
}