package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a parameter is picked, read off its declaration, and what each mode declares it as. */
class ChoiceModeTest {

    @Test
    void theModeIsReadOffTheTypeAndItsChoices() {
        assertEquals(ChoiceMode.NONE, ChoiceMode.of(int.class, List.of()));
        assertEquals(ChoiceMode.ONE, ChoiceMode.of(int.class, List.of("1", "5")));
        assertEquals(ChoiceMode.MANY, ChoiceMode.of(ValueTypes.listOf(int.class), List.of("1", "5")));
        // A list with no choices is typed freely: its base is the list itself.
        assertEquals(ChoiceMode.NONE, ChoiceMode.of(ValueTypes.listOf(int.class), List.of()));
        assertEquals(ValueTypes.listOf(int.class), ChoiceMode.base(ValueTypes.listOf(int.class), List.of()));
    }

    @Test
    void onlyTicksChangeTheDeclaredType() {
        assertEquals(ValueTypes.listOf(String.class), ChoiceMode.MANY.formFor(String.class));
        assertEquals(String.class, ChoiceMode.ONE.formFor(String.class));
        // Leaving ticks unboxes: a list's element is Integer, the field it came from was an int.
        assertEquals(int.class, ChoiceMode.NONE.formFor(Integer.class));
        assertEquals(Integer.class, ChoiceMode.base(ValueTypes.listOf(Integer.class), List.of("1")));
    }

    @Test
    void aFlagIsNeverPickedOneOfTwo() {
        assertEquals(List.of(ChoiceMode.NONE, ChoiceMode.MANY), ChoiceMode.offered(boolean.class, true));
        assertEquals(List.of(ChoiceMode.NONE, ChoiceMode.ONE, ChoiceMode.MANY), ChoiceMode.offered(int.class, true));
        assertEquals(List.of(ChoiceMode.NONE), ChoiceMode.offered(DayOfWeek.class, true));
        assertEquals(List.of(ChoiceMode.NONE), ChoiceMode.offered(ValueTypes.listOf(int.class), true));
        assertEquals(List.of(ChoiceMode.NONE), ChoiceMode.offered(int.class, false));
    }
}
