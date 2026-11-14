package com.reddis.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteCommandsIsWriteTest {

    @Test
    void writeCommandsAreRecognised() {
        for (String name : new String[]{"SET", "DEL", "MSET", "LPUSH", "RPOP", "HSET", "SREM", "ZADD", "EXPIRE", "PEXPIREAT"}) {
            assertTrue(WriteCommands.isWrite(name), name);
        }
    }

    @Test
    void readsAndAdministrativeCommandsAreNot() {
        for (String name : new String[]{"GET", "MGET", "LRANGE", "TTL", "PUBLISH", "INFO", "SAVE", "PING", "REPLICAOF"}) {
            assertFalse(WriteCommands.isWrite(name), name);
        }
    }
}