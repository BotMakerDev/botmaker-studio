package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which canvas slots get the container pill, and what it says (picker 6e2). */
class ContainerPickerTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();
    private static final Type LIST_OF_TEXT = ValueTypes.listOf(String.class);
    private static final Type MAP = ValueTypes.mapOf(String.class, int.class);

    @Test
    void theWordsCountWhatIsHeld() {
        assertEquals("List · 3 items", ContainerPicker.summary(LIST_OF_TEXT, 3));
        assertEquals("List · 1 item", ContainerPicker.summary(LIST_OF_TEXT, 1));
        assertEquals("Map · empty", ContainerPicker.summary(MAP, 0));
        assertEquals("Set · 2 items", ContainerPicker.summary(ValueTypes.of(ValueContainer.SET, List.of(String.class)), 2));
        assertEquals("Stack / queue · 2 items",
                ContainerPicker.summary(ValueTypes.of(ValueContainer.DEQUE, List.of(String.class)), 2));
    }

    @Test
    void aContainerWrittenAsItsOwnCallIsClaimed() {
        assertTrue(ContainerPicker.claims(GRAMMAR, LIST_OF_TEXT, "List.of(\"a\", \"b\")"));
        assertTrue(ContainerPicker.claims(GRAMMAR, MAP, "Map.ofEntries(Map.entry(\"a\", 1))"));
    }

    @Test
    void anEmptySlotKeepsItsOrdinaryMenu() {
        // Nothing is written there to replace, so a pill would open rows whose Apply could write nowhere.
        assertFalse(ContainerPicker.claims(GRAMMAR, LIST_OF_TEXT, null));
    }

    @Test
    void aVariableOrAnotherCallIsLeftAsWritten() {
        // Opening rows over `names` would replace a reference with a copy of what it held.
        assertFalse(ContainerPicker.claims(GRAMMAR, LIST_OF_TEXT, "names"));
        assertFalse(ContainerPicker.claims(GRAMMAR, LIST_OF_TEXT, "loadNames()"));
    }

    @Test
    void aTypeThatIsNoContainerIsNotClaimed() {
        assertFalse(ContainerPicker.claims(GRAMMAR, String.class, "\"a\""));
        assertFalse(ContainerPicker.claims(GRAMMAR, ValueTypes.NONE, "List.of()"));
    }
}
