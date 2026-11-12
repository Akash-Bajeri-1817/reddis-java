package com.reddis.session;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    /** ISO-8859-1 maps chars to bytes 1:1, so even bytes above 127 can be written in a test. */
    private static boolean m(String pattern, String text) {
        return GlobMatcher.matches(pattern.getBytes(StandardCharsets.ISO_8859_1), text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void literalPatternsMatchOnlyThemselves() {
        assertTrue(m("hello", "hello"));
        assertFalse(m("hello", "hell"));
        assertFalse(m("hello", "helloo"));
        assertFalse(m("hello", "Hello"));
    }

    @Test
    void starMatchesAnySequenceIncludingNone() {
        assertTrue(m("*", ""));
        assertTrue(m("*", "anything"));
        assertTrue(m("h*llo", "hllo"));
        assertTrue(m("h*llo", "heeeello"));
        assertFalse(m("h*llo", "hell"));
        assertTrue(m("news.*", "news.sports"));
        assertTrue(m("news.*", "news."));
        assertFalse(m("news.*", "news"));
    }

    @Test
    void questionMarkMatchesExactlyOneByte() {
        assertTrue(m("h?llo", "hello"));
        assertTrue(m("h?llo", "hallo"));
        assertFalse(m("h?llo", "hllo"));
        assertFalse(m("h?llo", "heello"));
    }

    @Test
    void bracketsMatchOneOfTheListedCharacters() {
        assertTrue(m("h[ae]llo", "hello"));
        assertTrue(m("h[ae]llo", "hallo"));
        assertFalse(m("h[ae]llo", "hillo"));
    }

    @Test
    void rangesAndNegationWork() {
        assertTrue(m("h[a-b]llo", "hallo"));
        assertTrue(m("h[a-b]llo", "hbllo"));
        assertFalse(m("h[a-b]llo", "hcllo"));
        assertTrue(m("h[b-a]llo", "hallo")); // a reversed range still works
        assertTrue(m("h[^e]llo", "hallo"));
        assertFalse(m("h[^e]llo", "hello"));
        assertTrue(m("news.[a-c]?", "news.b1"));
        assertFalse(m("news.[a-c]?", "news.d1"));
    }

    @Test
    void aClassCanHoldSeveralRangesAndEscapes() {
        assertTrue(m("[a-cx-z]", "y"));
        assertFalse(m("[a-cx-z]", "m"));
        assertTrue(m("[a-c\\]]", "]"));
    }

    @Test
    void backslashEscapesSpecialCharacters() {
        assertTrue(m("h\\*llo", "h*llo"));
        assertFalse(m("h\\*llo", "heello"));
        assertTrue(m("a\\?b", "a?b"));
        assertFalse(m("a\\?b", "axb"));
        assertTrue(m("a\\\\b", "a\\b"));
    }

    @Test
    void starsBacktrackCorrectly() {
        assertTrue(m("*a*b", "aaab"));
        assertFalse(m("*a*b", "aaac"));
        assertTrue(m("a*b*c", "aXbYbZc"));
        assertTrue(m("**", "abc"));
        assertTrue(m("a*", "a"));
        assertTrue(m("*a", "ba"));
        assertFalse(m("*a", "ab"));
    }

    @Test
    void anEmptyPatternMatchesOnlyTheEmptyString() {
        assertTrue(m("", ""));
        assertFalse(m("", "a"));
    }

    @Test
    void anUnterminatedBracketIsAnOrdinaryCharacter() {
        assertTrue(m("h[ello", "h[ello"));
        assertFalse(m("h[ello", "hello"));
    }

    @Test
    void bytesAbove127AreComparedAsUnsigned() {
        assertTrue(m("\u00ff*", "\u00ffabc"));
        assertTrue(m("[\u0080-\u00ff]", "\u00c8"));
        assertFalse(m("[\u0080-\u00ff]", "a"));
    }
}