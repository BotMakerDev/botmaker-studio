package com.botmaker.studio.nav;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSearchTest {

    private static final String SOURCE = """
            class Collect {
                // mine the ORE first
                static void body() { Pictures.ORE.click(); }
            }
            """;

    @Test
    void findsEveryOccurrenceIgnoringCase() {
        int first = SOURCE.indexOf("ORE");
        assertEquals(List.of(first, SOURCE.indexOf("ORE", first + 1)), TextSearch.offsets(SOURCE, "ore"));
    }

    @Test
    void aBlankQueryFindsNothing() {
        assertTrue(TextSearch.offsets(SOURCE, "").isEmpty());
    }

    @Test
    void occurrencesDoNotOverlap() {
        assertEquals(List.of(0, 2), TextSearch.offsets("aaaa", "aa"));
    }

    @Test
    void eachMatchCarriesItsLineAndTheLineTrimmed() {
        List<TextSearch.Match> matches = TextSearch.matches(SOURCE, "ORE");

        assertEquals(2, matches.get(0).line());
        assertEquals("// mine the ORE first", matches.get(0).lineText());
        assertEquals(3, matches.get(1).line());
        assertEquals("static void body() { Pictures.ORE.click(); }", matches.get(1).lineText());
    }

    @Test
    void aProjectSearchStopsAtItsLimit() {
        List<TextSearch.Source> sources = List.of(
                new TextSearch.Source(Path.of("A.java"), "x x x"),
                new TextSearch.Source(Path.of("B.java"), "x x"));

        List<TextSearch.FileMatch> hits = TextSearch.inFiles(sources, "x", 4);

        assertEquals(4, hits.size());
        assertEquals(Path.of("B.java"), hits.get(3).file());
    }
}
