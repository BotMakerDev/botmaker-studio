package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.source.ManagedValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An open set's declaration names the class of each constant, and {@code PluginValues.add} refuses a value of
 * another one (2026-10-05) — which would otherwise compile into the set and read back as the wrong type.
 */
class HostPluginValuesElementTest {

    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.openSet("words").of(CharSequence.class).in("Words").because("Mine."),
            ManagedValue.method("words-too").in("Values").holds(Integer.class, 1).because("Mine."));

    @Test
    void aValueOfTheElementTypeOrASubtypeJoins() {
        assertEquals(Optional.empty(), HostPluginValues.notAnElement(DECLARED, "words", "HELLO", "hello"));
        assertEquals(Optional.empty(),
                HostPluginValues.notAnElement(DECLARED, "words", "HELLO", new StringBuilder("hello")));
    }

    @Test
    void aValueOfAnotherClassIsRefusedNamingBoth() {
        assertEquals(Optional.of("COUNT cannot join Words: it is an Integer, and every constant there is a"
                        + " CharSequence."),
                HostPluginValues.notAnElement(DECLARED, "words", "COUNT", 3));
    }

    @Test
    void aSetNoPluginDeclaresIsNotCheckedHere() {
        assertEquals(Optional.empty(), HostPluginValues.notAnElement(DECLARED, "words-too", "X", 3));
        assertEquals(Optional.empty(), HostPluginValues.notAnElement(DECLARED, "nobody", "X", 3));
    }
}
