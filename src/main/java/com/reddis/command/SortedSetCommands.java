package com.reddis.command;

import com.reddis.protocol.RespValue;
import com.reddis.storage.DataStore;
import com.reddis.storage.ScoredMember;
import com.reddis.storage.SortedSetValue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** ZADD, ZSCORE, ZRANK, ZREM, ZCARD, ZRANGE, ZREVRANGE, ZINCRBY. */
public final class SortedSetCommands {

    private final DataStore store;

    private SortedSetCommands(DataStore store) {
        this.store = store;
    }

    public static void register(CommandRouter router, DataStore store) {
        SortedSetCommands commands = new SortedSetCommands(store);
        router.register("ZADD", commands::zadd);
        router.register("ZSCORE", commands::zscore);
        router.register("ZRANK", commands::zrank);
        router.register("ZREM", commands::zrem);
        router.register("ZCARD", commands::zcard);
        router.register("ZRANGE", args -> commands.zrange("zrange", args, false));
        router.register("ZREVRANGE", args -> commands.zrange("zrevrange", args, true));
        router.register("ZINCRBY", commands::zincrby);
    }

    /** ZADD key score member [score member ...] -> number of members that were NEW (updates not counted). */
    private RespValue zadd(List<byte[]> args) {
        if (args.size() < 3 || args.size() % 2 == 0) {
            return CommandArgs.wrongArgs("zadd");
        }

        // Validate EVERY score before touching the store, so one bad score changes nothing.
        int pairs = (args.size() - 1) / 2;
        double[] scores = new double[pairs];
        for (int i = 0; i < pairs; i++) {
            OptionalDouble score = CommandArgs.parseScore(args.get(1 + 2 * i));
            if (score.isEmpty()) {
                return CommandArgs.NOT_A_FLOAT;
            }
            scores[i] = score.getAsDouble();
        }

        int added = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new, zset -> {
            int count = 0;
            for (int i = 0; i < pairs; i++) {
                if (zset.add(args.get(2 + 2 * i), scores[i])) {
                    count++;
                }
            }
            return count;
        });
        return new RespValue.RespInteger(added);
    }

    private RespValue zscore(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("zscore");
        }
        byte[] member = args.get(1);
        Double score = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new,
                zset -> zset.score(member));
        return score == null ? RespValue.BulkString.NULL : CommandArgs.bulk(CommandArgs.formatScore(score));
    }

    private RespValue zrank(List<byte[]> args) {
        if (args.size() != 2) {
            return CommandArgs.wrongArgs("zrank");
        }
        byte[] member = args.get(1);
        int rank = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new, zset -> zset.rank(member));
        return rank < 0 ? RespValue.BulkString.NULL : new RespValue.RespInteger(rank);
    }

    private RespValue zrem(List<byte[]> args) {
        if (args.size() < 2) {
            return CommandArgs.wrongArgs("zrem");
        }
        List<byte[]> members = args.subList(1, args.size());
        int removed = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new, zset -> {
            int count = 0;
            for (byte[] member : members) {
                if (zset.remove(member)) {
                    count++;
                }
            }
            return count;
        });
        return new RespValue.RespInteger(removed);
    }

    private RespValue zcard(List<byte[]> args) {
        if (args.size() != 1) {
            return CommandArgs.wrongArgs("zcard");
        }
        int size = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new, SortedSetValue::size);
        return new RespValue.RespInteger(size);
    }

    /** ZRANGE / ZREVRANGE key start stop [WITHSCORES]. */
    private RespValue zrange(String name, List<byte[]> args, boolean reverse) {
        if (args.size() != 3 && args.size() != 4) {
            return CommandArgs.wrongArgs(name);
        }
        boolean withScores = false;
        if (args.size() == 4) {
            if (!CommandArgs.keyword(args.get(3)).equals("WITHSCORES")) {
                return CommandArgs.SYNTAX_ERROR;
            }
            withScores = true;
        }
        OptionalLong start = CommandArgs.parseLong(args.get(1));
        OptionalLong stop = CommandArgs.parseLong(args.get(2));
        if (start.isEmpty() || stop.isEmpty()) {
            return CommandArgs.NOT_AN_INTEGER;
        }

        long from = start.getAsLong();
        long to = stop.getAsLong();
        List<ScoredMember> items = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new,
                zset -> zset.range(from, to, reverse));

        List<RespValue> elements = new ArrayList<>(items.size() * (withScores ? 2 : 1));
        for (ScoredMember item : items) {
            elements.add(new RespValue.BulkString(item.member()));
            if (withScores) {
                elements.add(CommandArgs.bulk(CommandArgs.formatScore(item.score())));
            }
        }
        return new RespValue.RespArray(elements);
    }

    /** ZINCRBY key increment member -> the new score. Creates the member (starting from 0) if needed. */
    private RespValue zincrby(List<byte[]> args) {
        if (args.size() != 3) {
            return CommandArgs.wrongArgs("zincrby");
        }
        OptionalDouble increment = CommandArgs.parseScore(args.get(1));
        if (increment.isEmpty()) {
            return CommandArgs.NOT_A_FLOAT;
        }
        double delta = increment.getAsDouble();
        byte[] member = args.get(2);

        // Read-modify-write inside ONE access() call, so it is atomic.
        double result = store.access(args.get(0), SortedSetValue.class, SortedSetValue::new, zset -> {
            Double current = zset.score(member);
            double next = (current == null ? 0.0 : current) + delta;
            if (!Double.isNaN(next)) {
                zset.add(member, next);
            }
            return next;
        });

        if (Double.isNaN(result)) { // inf + -inf: nothing was stored
            return new RespValue.RespError("ERR resulting score is not a number (NaN)");
        }
        return CommandArgs.bulk(CommandArgs.formatScore(result));
    }
}