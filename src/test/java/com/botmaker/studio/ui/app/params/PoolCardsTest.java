package com.botmaker.studio.ui.app.params;

import com.botmaker.core.project.params.PoolEdits;
import com.botmaker.java.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A card's words about several runs (2026-11-16). */
class PoolCardsTest {

    @Test
    void aPoolSaysWhatItDealsInPlainWords() {
        assertEquals("⛁ Pool · 2 listed · one a run · up to 3 runs each", PoolCards.strip(shape(String.class,
                String.class, false, List.of("\"a\"", "\"b\""), 0, 3, false)));
        assertEquals("⛁ Pool · none listed yet · 2 a run · rotating", PoolCards.strip(shape(
                ValueTypes.listOf(String.class), String.class, false, List.of(), 2, 1, true)));
        assertEquals("⛁ Pool · every DayOfWeek · one a run", PoolCards.strip(shape(DayOfWeek.class,
                DayOfWeek.class, true, List.of(), 0, 1, false)));
    }

    @Test
    void allIsOfferedForAnEnumAndTakeForAList() {
        assertTrue(PoolCards.offersAll(shape(DayOfWeek.class, DayOfWeek.class, false, List.of(), 0, 1, false)));
        assertFalse(PoolCards.offersAll(shape(String.class, String.class, false, List.of(), 0, 1, false)));
        assertTrue(PoolCards.isList(ValueTypes.listOf(String.class)));
        assertEquals(String.class, PoolCards.listElement(ValueTypes.listOf(String.class)).orElseThrow());
        assertFalse(PoolCards.isList(String.class));
    }

    private static PoolEdits.Shape shape(Type parameter, Type element,
                                         boolean all, List<String> elements, int take, int each, boolean rotates) {
        return new PoolEdits.Shape("FarmPools", "x", "Farm", "x", parameter, element, all, elements, List.of(), take,
                each, rotates);
    }
}
