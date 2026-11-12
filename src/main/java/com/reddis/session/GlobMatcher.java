package com.reddis.session;

/**
 * Redis-style glob matching on raw bytes: * (any sequence), ? (one byte), [abc], [a-z], [^abc] and \ to escape.
 * Two indexes walk pattern and text; on a mismatch we BACKTRACK to the last * and let it swallow one more byte.
 */
public final class GlobMatcher {

    private GlobMatcher() { }

    public static boolean matches(byte[] pattern, byte[] text) {
        int p = 0;
        int t = 0;
        int starPattern = -1; // where the last * is in the pattern
        int starText = 0;     // where in the text that * currently ends

        while (t < text.length) {
            if (p < pattern.length && pattern[p] == '*') {
                starPattern = p;
                starText = t;
                p++;
                continue;
            }
            if (p < pattern.length) {
                int consumed = matchOne(pattern, p, text[t] & 0xFF);
                if (consumed > 0) {
                    p += consumed;
                    t++;
                    continue;
                }
            }
            if (starPattern >= 0) { // mismatch: let the last * take one more byte and retry
                p = starPattern + 1;
                t = ++starText;
                continue;
            }
            return false;
        }
        while (p < pattern.length && pattern[p] == '*') {
            p++; // trailing stars match the empty rest
        }
        return p == pattern.length;
    }

    /** Tries to match ONE text byte at pattern position p. @return pattern bytes consumed, or -1 if no match */
    private static int matchOne(byte[] pattern, int p, int b) {
        int c = pattern[p] & 0xFF;
        if (c == '?') {
            return 1;
        }
        if (c == '\\') {
            if (p + 1 < pattern.length) {
                return (pattern[p + 1] & 0xFF) == b ? 2 : -1;
            }
            return b == '\\' ? 1 : -1; // a trailing backslash is an ordinary character
        }
        if (c == '[') {
            return matchClass(pattern, p, b);
        }
        return c == b ? 1 : -1;
    }

    private static int matchClass(byte[] pattern, int start, int b) {
        int i = start + 1;
        boolean negate = false;
        if (i < pattern.length && pattern[i] == '^') {
            negate = true;
            i++;
        }

        boolean matched = false;
        while (i < pattern.length && pattern[i] != ']') {
            int c = pattern[i] & 0xFF;
            if (c == '\\' && i + 1 < pattern.length) {
                if ((pattern[i + 1] & 0xFF) == b) {
                    matched = true;
                }
                i += 2;
            } else if (i + 2 < pattern.length && pattern[i + 1] == '-' && pattern[i + 2] != ']') {
                int low = c;
                int high = pattern[i + 2] & 0xFF;
                if (low > high) {
                    int swap = low;
                    low = high;
                    high = swap;
                }
                if (b >= low && b <= high) {
                    matched = true;
                }
                i += 3;
            } else {
                if (c == b) {
                    matched = true;
                }
                i++;
            }
        }

        if (i >= pattern.length) { // no closing bracket: the '[' is an ordinary character
            return b == '[' ? 1 : -1;
        }
        return matched != negate ? i - start + 1 : -1;
    }
}