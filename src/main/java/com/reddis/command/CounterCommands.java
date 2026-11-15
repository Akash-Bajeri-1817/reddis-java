package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.NotAnIntegerException;

import java.util.List;
import java.util.OptionalLong;

/** INCR, DECR, INCRBY, DECRBY: atomic counters stored as strings. */
public final class CounterCommands {

    private static final RespValue OVERFLOW = new RespValue.RespError("ERR increment or decrement would overflow");

    private CounterCommands() { }

    public static void register(CommandRouter router, DataStore store) {
        router.register("INCR", args -> single("incr", store, args, 1));
        router.register("DECR", args -> single("decr", store, args, -1));
        router.register("INCRBY", args -> byAmount("incrby", store, args, false));
        router.register("DECRBY", args -> byAmount("decrby", store, args, true));
    }

    private static RespValue single(String name, DataStore store, List<byte[]> args, long delta) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs(name);
        }
        return apply(store, args.get(0), delta);
    }

    private static RespValue byAmount(String name, DataStore store, List<byte[]> args, boolean negate) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs(name);
        }
        OptionalLong amount = CommandArgs.parseLong(args.get(1));
        if (amount.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }
        long delta = amount.getAsLong();
        if (negate) {
            try {
                delta = Math.negateExact(delta); // -Long.MIN_VALUE does not fit
            } catch (ArithmeticException e) {
                return OVERFLOW;
            }
        }
        return apply(store, args.get(0), delta);
    }

    private static RespValue apply(DataStore store, byte[] key, long delta) {
        try {
            return new RespValue.RespInteger(store.incrementBy(key, delta)); // WRONGTYPE is turned into a reply by the router
        } catch (NotAnIntegerException e) {
            return CommandArgs.NOT_AN_INTEGER;
        } catch (ArithmeticException e) {
            return OVERFLOW;
        }
    }
}