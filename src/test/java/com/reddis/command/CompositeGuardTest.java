package com.reddis.command;

import com.reddis.protocol.RespValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompositeGuardTest {

    private static final RespValue FIRST = new RespValue.RespError("first");
    private static final RespValue SECOND = new RespValue.RespError("second");

    @Test
    void theFirstGuardThatRefusesDecides() {
        CompositeGuard guard = new CompositeGuard(name -> null, name -> FIRST, name -> SECOND);
        assertEquals(FIRST, guard.beforeExecute("SET"));
    }

    @Test
    void whenNoGuardRefusesTheCommandMayRun() {
        assertNull(new CompositeGuard(name -> null, name -> null).beforeExecute("SET"));
        assertNull(new CompositeGuard().beforeExecute("SET"));
    }
}