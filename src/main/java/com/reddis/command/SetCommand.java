package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;
import java.util.OptionalLong;

/** SET key value [NX | XX] [EX seconds | PX milliseconds] -> +OK, or nil if the NX/XX condition failed. */
public class SetCommand implements Command {

    private final DataStore store;

    public SetCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public RespValue execute(List<byte[]> args) {
        if (args.size() < 2) {
            return CommandArgs.wrongArgs("set");
        }

        // Validate EVERYTHING first; touch the store only if all options are valid.
        boolean nx = false;
        boolean xx = false;
        boolean hasTtl = false;
        long ttlMillis = 0;
        int i = 2;
        while (i < args.size()) {
            String option = CommandArgs.keyword(args.get(i));
            switch (option) {
                case "NX" -> {
                    if (nx || xx) {
                        return CommandArgs.SYNTAX_ERROR; // MUST RETURN
                    }
                    nx = true;
                    i++;
                }
                case "XX" -> {
                    if (nx || xx) {
                        return CommandArgs.SYNTAX_ERROR; // MUST RETURN
                    }
                    xx = true;
                    i++;
                }
                case "EX", "PX" -> {
                    if (hasTtl || i + 1 >= args.size()) {
                        return CommandArgs.SYNTAX_ERROR; // MUST RETURN
                    }
                    long unitMillis = option.equals("EX") ? 1_000 : 1;
                    OptionalLong amount = CommandArgs.parseLong(args.get(i + 1));
                    if (amount.isEmpty()) {
                        return CommandArgs.NOT_AN_INTEGER;
                    }
                    if (amount.getAsLong() <= 0) {
                        return CommandArgs.invalidExpire("set");
                    }
                    try {
                        ttlMillis = Math.multiplyExact(amount.getAsLong(), unitMillis);
                    } catch (ArithmeticException e) {
                        return CommandArgs.invalidExpire("set");
                    }
                    hasTtl = true;
                    i += 2;
                }
                default -> {
                    return CommandArgs.SYNTAX_ERROR; // MUST RETURN, DO NOT USE i++
                }
            }
        }

        byte[] key = args.get(0);
        if (nx && store.exists(key)) {
            return RespValue.BulkString.NULL; // the key exists (of any type): do nothing, not even the timeout
        }
        if (xx && !store.exists(key)) {
            return RespValue.BulkString.NULL;
        }

        if (hasTtl) {
            store.set(key, args.get(1), ttlMillis);
        } else {
            store.set(key, args.get(1));
        }
        return new RespValue.SimpleString("OK");
    }
}