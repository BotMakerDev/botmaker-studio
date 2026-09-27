package com.botmaker.studio.services.debug;

import com.botmaker.studio.services.debug.DebugSnapshot.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A paused value in one short line, read off field names alone: Studio names no type of a plugin, so a
 * result reads as "found at 120, 340 · 93%" because it has {@code found}, {@code location} and
 * {@code confidence}, whatever its class is called.
 */
class ValueSummaryTest {

    private static Variable leaf(String name, String value) {
        return new Variable(name, "", value, List.of());
    }

    private static Variable object(String name, String type, Variable... fields) {
        return new Variable(name, type, type + " #12", List.of(fields));
    }

    private static Variable point(String name, int x, int y) {
        return object(name, "Point", leaf("x", String.valueOf(x)), leaf("y", String.valueOf(y)));
    }

    @Test
    void aFoundMatchSaysWhereAndHowSure() {
        Variable match = object("m", "MatchResult", point("location", 120, 340), leaf("width", "40"),
                leaf("confidence", "0.9312"), leaf("found", "true"), leaf("templateId", "\"ore\""));
        assertEquals("found at 120, 340 · 93%", ValueSummary.of(match));
    }

    @Test
    void aMissSaysNotFound() {
        Variable match = object("m", "MatchResult", leaf("location", "null"), leaf("confidence", "0.0"),
                leaf("found", "false"));
        assertEquals("not found", ValueSummary.of(match));
    }

    @Test
    void readTextIsQuotedAndItsConfidenceIsAlreadyAPercentage() {
        Variable text = object("t", "TextMatch", leaf("text", "\"Start\""),
                object("bounds", "Rect", leaf("x", "10"), leaf("y", "20"), leaf("width", "5"), leaf("height", "5")),
                leaf("confidence", "87.4"), leaf("found", "true"));
        assertEquals("“Start” at 10, 20 · 87%", ValueSummary.of(text));
    }

    @Test
    void aColourMatchCountsItsPixels() {
        Variable colour = object("c", "ColorMatch", point("location", 5, 6), leaf("pixelCount", "420"),
                leaf("found", "true"));
        assertEquals("found at 5, 6 · 420 px", ValueSummary.of(colour));
    }

    @Test
    void anObjectHoldingOnlyACollectionSaysHowManyItHolds() {
        Variable matches = object("all", "Matches",
                new Variable("byTemplateId", "Map", "Collections.UnmodifiableMap (3)", List.of(), 3));
        assertEquals("3 found", ValueSummary.of(matches));
        Variable bag = object("bag", "Bag", new Variable("items", "List", "ArrayList (0)", List.of(), 0));
        assertEquals("empty", ValueSummary.of(bag));
    }

    @Test
    void aPlainValueIsShownAsReadAndAnyOtherObjectListsItsFirstFields() {
        assertEquals("42", ValueSummary.of(leaf("n", "42")));
        assertEquals("\"hero\"", ValueSummary.of(leaf("s", "\"hero\"")));
        assertEquals("Point{x=1, y=2}", ValueSummary.of(point("p", 1, 2)));
        Variable wide = object("w", "Wide", leaf("a", "1"), leaf("b", "2"), leaf("c", "3"), leaf("d", "4"));
        assertEquals("Wide{a=1, b=2, c=3, …}", ValueSummary.of(wide));
    }

    @Test
    void theTooltipIsTheWholeTreeIndented() {
        Variable match = object("m", "MatchResult", point("location", 1, 2), leaf("found", "true"));
        assertEquals("""
                m = MatchResult #12
                  location = Point #12
                    x = 1
                    y = 2
                  found = true""", ValueSummary.tree(match));
    }
}
