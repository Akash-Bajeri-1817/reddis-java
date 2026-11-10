package com.reddis.command;

import com.reddis.storage.DataStore;

/** Builds a router with all the data commands. (Persistence commands are added separately.) */
public final class CommandRegistry {

    private CommandRegistry() { }

    public static CommandRouter standard(DataStore store) {
        CommandRouter router = new CommandRouter();
        router.register("PING", new PingCommand());
        router.register("ECHO", new EchoCommand());

        // strings and keys
        router.register("SET", new SetCommand(store));
        router.register("GET", new GetCommand(store));
        router.register("DEL", new DelCommand(store));
        router.register("EXISTS", new ExistsCommand(store));
        router.register("TYPE", new TypeCommand(store));
        router.register("DBSIZE", new DbSizeCommand(store));
        MultiKeyCommands.register(router, store);

        // expiry
        router.register("EXPIRE", new ExpireCommand("expire", store, 1_000));
        router.register("PEXPIRE", new ExpireCommand("pexpire", store, 1));
        router.register("EXPIREAT", new ExpireAtCommand("expireat", store, 1_000));
        router.register("PEXPIREAT", new ExpireAtCommand("pexpireat", store, 1));
        router.register("TTL", new TtlCommand("ttl", store, false));
        router.register("PTTL", new TtlCommand("pttl", store, true));
        router.register("PERSIST", new PersistCommand(store));

        // data structures
        ListCommands.register(router, store);
        HashCommands.register(router, store);
        SetTypeCommands.register(router, store);
        SortedSetCommands.register(router, store);
        return router;
    }
}