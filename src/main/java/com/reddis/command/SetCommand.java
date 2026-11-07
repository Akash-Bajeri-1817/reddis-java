package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;

import java.util.List;
import java.util.OptionalLong;

/** SET key value [EX seconds | PX milliseconds] -> +OK */
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
        boolean hasTtl = false;
        long ttlMillis = 0;
        int i = 2;
        while (i < args.size()) {
            String option = CommandArgs.keyword(args.get(i));
            long unitMillis;
            if (option.equals("EX")) {
                unitMillis = 1_000;
            } else if (option.equals("PX")) {
                unitMillis = 1;
            } else {
                return CommandArgs.SYNTAX_ERROR;
            }
            if (hasTtl || i + 1 >= args.size()) {
                return CommandArgs.SYNTAX_ERROR; // EX/PX given twice, or no amount after it
            }

            OptionalLong amount = CommandArgs.parseLong(args.get(i + 1));
            if (amount.isEmpty()) {
                return CommandArgs.NOT_AN_INTEGER;
            }
            if (amount.getAsLong() <= 0) {
                return CommandArgs.invalidExpire("set");
            }
            try {
                ttlMillis = Math.multiplyExact(amount.getAsLong(), unitMillis); // throws on overflow
            } catch (ArithmeticException e) {
                return CommandArgs.invalidExpire("set");
            }
            hasTtl = true;
            i += 2;
        }

        if (hasTtl) {
            store.set(args.get(0), args.get(1), ttlMillis);
        } else {
            store.set(args.get(0), args.get(1));
        }
        return new RespValue.SimpleString("OK");
    }
}