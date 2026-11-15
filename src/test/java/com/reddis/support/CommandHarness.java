package com.reddis.support;

import com.reddis.command.CommandRegistry;
import com.reddis.command.CommandRouter;
import com.reddis.storage.DataStore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Test helper: a store with a controllable clock, the standard commands, and run(...) returning rendered text. */
public final class CommandHarness {

    public final MutableClock clock = new MutableClock(1_000_000L);
    public final DataStore store = new DataStore(clock);
    public final CommandRouter router = CommandRegistry.standard(store);

    public String run(String... parts) {
        List<byte[]> command = Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toList();
        return RespText.render(router.route(command));
    }
}