package com.botmaker.studio.project.params;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A row's defaults and its value equality — the window compares a row before and after an edit, so equality
 * is load-bearing rather than cosmetic. Moved from the contract's {@code ParameterDataTest} with the row
 * (2026-09-28).
 */
class ParameterRowTest {

    private static ParameterRow.Builder row(String name) {
        return ParameterRow.named(name, "String");
    }

    @Test
    void aRowNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> ParameterRow.named("  ", "String"));
        assertThrows(IllegalArgumentException.class, () -> ParameterRow.named(null, "String"));
    }

    /** Every default is the reading that keeps a project open: a value, a visibility and a range all absent. */
    @Test
    void everyDefaultIsTheSafeReading() {
        ParameterRow bare = row("rest").build();

        assertEquals("", bare.value());
        assertEquals("", bare.description());
        assertEquals("rest", bare.displayLabel());
        assertEquals("", bare.category());
        assertEquals(ParameterRow.GENERAL, bare.categoryOrGeneral());
        assertEquals(Visibility.PUBLIC, bare.visibility());
        assertTrue(bare.isPublic());
        assertEquals(List.of(), bare.options());
        assertFalse(bare.isBounded());
        assertEquals(Double.NEGATIVE_INFINITY, bare.min());
        assertEquals(Double.POSITIVE_INFINITY, bare.max());
    }

    /** A row with no type at all is blank rather than a {@code null} the host would trip over. */
    @Test
    void aRowWithNoTypeHoldsABlankOne() {
        ParameterRow untyped = ParameterRow.named("legacy", null).value("kept").build();

        assertEquals("", untyped.typeName());
        assertEquals("kept", untyped.value());
    }

    @Test
    void aRowCarriesItsTypeAsWritten() {
        assertEquals("java.util.List<String>", ParameterRow.named("keys", "java.util.List<String>")
                .build().typeName());
        assertEquals("Map<String, List<Point>>", ParameterRow.named("retries", "Map<String, List<Point>>")
                .build().typeName());
    }

    /** One end of a range is a sentence a person says, and both ends are independent. */
    @Test
    void oneEndOfARangeIsEnough() {
        ParameterRow atMost = row("attempts").bounds(Double.NEGATIVE_INFINITY, 10).build();

        assertTrue(atMost.isBounded());
        assertEquals(10, atMost.max());
        assertEquals(Double.NEGATIVE_INFINITY, atMost.min());
    }

    @Test
    void withValueKeepsEverythingElseAndEqualityIsByComponent() {
        ParameterRow declared = ParameterRow.named("rest", "java.time.Duration")
                .value("java.time.Duration.ofSeconds(3)").description("How long to wait").category("Timing")
                .visibility(Visibility.EDITOR_ONLY)
                .options(List.of("java.time.Duration.ofSeconds(3)", "java.time.Duration.ofSeconds(5)"))
                .bounds(1000, 9000)
                .build();

        ParameterRow edited = declared.withValue("java.time.Duration.ofSeconds(5)");

        assertEquals("java.time.Duration.ofSeconds(5)", edited.value());
        assertNotEquals(declared, edited);
        assertEquals(declared, edited.withValue("java.time.Duration.ofSeconds(3)"));
        assertEquals(declared.hashCode(), edited.withValue("java.time.Duration.ofSeconds(3)").hashCode());
        assertEquals(declared, declared.toBuilder().build());
        assertEquals("How long to wait", edited.displayLabel());
        assertEquals("Timing", edited.categoryOrGeneral());
    }

    /** A category is free text the window files a row under — trimmed, and never a vocabulary. */
    @Test
    void aCategoryIsFreeTextTheWindowFilesARowUnder() {
        ParameterRow filed = row("rest").category("  Timing ").build();

        assertEquals("Timing", filed.category());
        assertEquals(ParameterRow.GENERAL, row("rest").build().categoryOrGeneral());
    }

    @Test
    void anUnknownVisibilityIdReadsAsEditorOnly() {
        assertEquals(Visibility.PUBLIC, Visibility.fromId("public"));
        assertEquals(Visibility.EDITOR_ONLY, Visibility.fromId("something newer"));
        assertEquals(Visibility.EDITOR_ONLY, Visibility.fromId(null));
    }
}
