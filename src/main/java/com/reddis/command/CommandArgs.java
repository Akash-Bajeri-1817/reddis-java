package com.reddis.command;

import com.reddis.protocol.RespValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** Small helpers shared by commands. */
public final class CommandArgs {

    static final RespValue NOT_AN_INTEGER = new RespValue.RespError("ERR value is not an integer or out of range");
    static final RespValue NOT_A_FLOAT = new RespValue.RespError("ERR value is not a valid float");
    static final RespValue SYNTAX_ERROR = new RespValue.RespError("ERR syntax error");

    /** Plain decimal numbers with optional sign and exponent. Stricter than Double.parseDouble on purpose. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private CommandArgs() { }

    /** Parses a decimal integer argument; empty if it is not one (or does not fit in a long). */
    static OptionalLong parseLong(byte[] arg) {
        try {
            return OptionalLong.of(Long.parseLong(new String(arg, StandardCharsets.US_ASCII)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Parses a sorted-set score: a decimal number, "inf", "+inf" or "-inf" (case-insensitive).
     * Rejects NaN, hex floats, suffixes such as "1.5f" (all accepted by Double.parseDouble) and overflow.
     */
    static OptionalDouble parseScore(byte[] arg) {
        String text = new String(arg, StandardCharsets.US_ASCII);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("inf") || lower.equals("+inf")) {
            return OptionalDouble.of(Double.POSITIVE_INFINITY);
        }
        if (lower.equals("-inf")) {
            return OptionalDouble.of(Double.NEGATIVE_INFINITY);
        }
        if (!DECIMAL.matcher(text).matches()) {
            return OptionalDouble.empty();
        }
        double value = Double.parseDouble(text);
        return Double.isInfinite(value) ? OptionalDouble.empty() : OptionalDouble.of(value); // e.g. "1e999"
    }

    /** Prints a score like Redis: 1 (not 1.0), 2.5, -3, 100 (not 1.0E2), inf, -inf. */
    public static String formatScore(double score) {
        if (score == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (score == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        return BigDecimal.valueOf(score).stripTrailingZeros().toPlainString();
    }

    /** Keyword arguments such as EX/PX/WITHSCORES are case-insensitive. */
    static String keyword(byte[] arg) {
        return new String(arg, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }

    static RespValue wrongArgs(String commandName) {
        return new RespValue.RespError("ERR wrong number of arguments for '" + commandName + "' command");
    }

    static RespValue invalidExpire(String commandName) {
        return new RespValue.RespError("ERR invalid expire time in '" + commandName + "' command");
    }

    /** A bulk string, or the null bulk string when the value is null. */
    static RespValue bulkOrNull(byte[] value) {
        return value == null ? RespValue.BulkString.NULL : new RespValue.BulkString(value);
    }

    /** A bulk string holding the text (UTF-8). */
    static RespValue bulk(String text) {
        return new RespValue.BulkString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A RESP array whose elements are bulk strings. */
    static RespValue bulkArray(List<byte[]> items) {
        List<RespValue> elements = new ArrayList<>(items.size());
        for (byte[] item : items) {
            elements.add(new RespValue.BulkString(item));
        }
        return new RespValue.RespArray(elements);
    }
}