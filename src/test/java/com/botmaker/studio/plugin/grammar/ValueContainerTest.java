package com.botmaker.studio.plugin.grammar;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's three containers, and nothing about one is a string function.
 *
 * <p>They were an open contribution until 2026-09-22, which a plugin registered into a
 * {@code ValueCatalog}. A plugin's composite is a {@code ComponentType} now — {@code ValueGrammarTest}
 * holds that — and what is left here is only what the host seeds so a project with no plugin still has a
 * list and a map.
 */
class ValueContainerTest {

    private static final ValueForm TEXT = ValueForm.of(String.class);
    private static final ValueForm COUNT = ValueForm.of(int.class);

    // ---- the law every container owes ---------------------------------------------------------------------

    @Test
    void aListIsTakenApartAndPutBackTogether() {
        List<?> value = List.of("a", "b", "c");
        assertEquals(3, ValueContainer.LIST.parts(value).size());
        assertEquals(value, ValueContainer.LIST.build(ValueContainer.LIST.parts(value)));
    }

    @Test
    void aMapsPartsAreItsEntries() {
        Map<?, ?> value = Map.of("a", 1);
        List<Object> parts = ValueContainer.MAP.parts(value);
        assertEquals(1, parts.size());
        assertTrue(parts.getFirst() instanceof Map.Entry<?, ?>);
        assertEquals(value, ValueContainer.MAP.build(parts));
    }

    @Test
    void aMapKeepsTheOrderItWasGiven() {
        // Map.ofEntries specifies no iteration order, so building through one would rewrite a user's file
        // with its entries shuffled on some later run, for no change the user made.
        Map<String, Integer> value = new java.util.LinkedHashMap<>();
        value.put("z", 1);
        value.put("a", 2);
        value.put("m", 3);
        List<Object> parts = ValueContainer.MAP.parts(value);
        assertEquals(List.of("z", "a", "m"), parts.stream().map(p -> ((Map.Entry<?, ?>) p).getKey()).toList());
        assertEquals(List.of("z", "a", "m"), List.copyOf(ValueContainer.MAP.build(parts).keySet()));
    }

    @Test
    void anEntryIsAContainerOfItsOwn() {
        Map.Entry<?, ?> value = Map.entry("a", 1);
        assertEquals(List.of("a", 1), ValueContainer.ENTRY.parts(value));
        assertEquals(value, ValueContainer.ENTRY.build(List.of("a", 1)));
    }

    // ---- the syntax the host writes, derived once for every container -------------------------------------

    @Test
    void theFactoryCallIsDerivedNotSupplied() {
        assertEquals(List.class, ValueContainer.LIST.factory().owner());
        assertEquals("of", ValueContainer.LIST.factory().name());
        assertEquals("ofEntries", ValueContainer.MAP.factory().name());
        // Map.entry is declared on Map, not on Map.Entry — which is why the factory's owner is not the type.
        assertEquals(java.util.Map.class, ValueContainer.ENTRY.factory().owner());
        assertEquals("entry", ValueContainer.ENTRY.factory().name());
        assertEquals("java.util.Map.Entry", ValueContainer.ENTRY.sourceName());
        assertEquals("java.util.Map", ValueContainer.ENTRY.importName());
    }

    // ---- part forms are what keep the recursion typed ------------------------------------------------------

    @Test
    void aListsPartsAreAllItsElementType() {
        assertEquals(List.of(TEXT, TEXT, TEXT), ValueContainer.LIST.partForms(List.of(TEXT), 3));
    }

    @Test
    void aMapsPartsAreEntriesOverItsOwnArguments() {
        List<ValueForm> arguments = List.of(TEXT, COUNT);
        List<ValueForm> parts = ValueContainer.MAP.partForms(arguments, 2);
        assertEquals(2, parts.size());
        assertEquals(new ValueForm.Of(ValueContainer.ENTRY, arguments), parts.getFirst());
        // And an entry's own parts are the key and the value, positionally — so a walk of a map's values
        // reaches String then Integer without ever asking a runtime class what it is.
        assertEquals(arguments, ValueContainer.ENTRY.partForms(arguments, 2));
    }

    // ---- what a written type name means ----------------------------------------------------------------------

    @Test
    void aContainerIsFoundByAnyTrailingSpelling() {
        ValueGrammar grammar = ValueGrammar.empty();
        assertEquals(ValueContainer.LIST, grammar.containerForJava("List").orElseThrow());
        assertEquals(ValueContainer.LIST, grammar.containerForJava("java.util.List").orElseThrow());
        assertEquals(ValueContainer.ENTRY, grammar.containerForJava("Map.Entry").orElseThrow());
    }

    @Test
    void somethingNoContainerIsIsAnOrdinaryAbsence() {
        // A Set, an array, a class nobody declared. Empty rather than a throw: the host then shows the
        // field's source read-only, which is what it already does for an unknown leaf.
        assertFalse(ValueGrammar.empty().containerForJava("java.util.Set").isPresent());
        assertFalse(ValueGrammar.empty().containerForJava("").isPresent());
    }
}
