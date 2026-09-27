package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a parameter is picked, read off its declaration, and what each mode declares it as. */
class ChoiceModeTest {

    private static final ValueGrammar GRAMMAR = TestValues.GRAMMAR;

    @Test
    void theModeIsReadOffTheTypeAndItsChoices() {
        assertEquals(ChoiceMode.NONE, ChoiceMode.of(int.class, List.of(), GRAMMAR));
        assertEquals(ChoiceMode.ONE, ChoiceMode.of(int.class, List.of("1", "5"), GRAMMAR));
        assertEquals(ChoiceMode.MANY, ChoiceMode.of(ValueTypes.listOf(int.class), List.of("1", "5"), GRAMMAR));
        // A list with no choices is typed freely: its base is the list itself.
        assertEquals(ChoiceMode.NONE, ChoiceMode.of(ValueTypes.listOf(int.class), List.of(), GRAMMAR));
        assertEquals(ValueTypes.listOf(int.class),
                ChoiceMode.base(ValueTypes.listOf(int.class), List.of(), GRAMMAR));
    }

    @Test
    void choicesThatAreWholeListsAreOneOfThoseLists() {
        // The options' own shape says which: a choice that reads as the declared list is one of several lists,
        // a choice that reads as its element is a tick.
        var numbers = ValueTypes.listOf(Integer.class);
        assertEquals(ChoiceMode.ONE, ChoiceMode.of(numbers, List.of("List.of(1, 2)", "List.of(3)"), GRAMMAR));
        assertEquals(numbers, ChoiceMode.base(numbers, List.of("List.of(1, 2)"), GRAMMAR));
        assertEquals(ChoiceMode.MANY, ChoiceMode.of(numbers, List.of("1", "2"), GRAMMAR));
        assertEquals(Integer.class, ChoiceMode.base(numbers, List.of("1", "2"), GRAMMAR));
    }

    @Test
    void onlyTicksChangeTheDeclaredType() {
        assertEquals(ValueTypes.listOf(String.class), ChoiceMode.MANY.formFor(String.class));
        assertEquals(String.class, ChoiceMode.ONE.formFor(String.class));
        // Leaving ticks unboxes: a list's element is Integer, the field it came from was an int.
        assertEquals(int.class, ChoiceMode.NONE.formFor(Integer.class));
        assertEquals(Integer.class, ChoiceMode.base(ValueTypes.listOf(Integer.class), List.of("1"), GRAMMAR));
    }

    @Test
    void aFlagIsNeverPickedOneOfTwoAndAListIsNeverTicked() {
        assertEquals(List.of(ChoiceMode.NONE, ChoiceMode.MANY), ChoiceMode.offered(boolean.class, true));
        assertEquals(List.of(ChoiceMode.NONE, ChoiceMode.ONE, ChoiceMode.MANY), ChoiceMode.offered(int.class, true));
        assertEquals(List.of(ChoiceMode.NONE), ChoiceMode.offered(DayOfWeek.class, true));
        // A list can be one of several lists; ticks over a list would be a list of lists.
        assertEquals(List.of(ChoiceMode.NONE, ChoiceMode.ONE),
                ChoiceMode.offered(ValueTypes.listOf(int.class), true));
        assertEquals(List.of(ChoiceMode.NONE), ChoiceMode.offered(int.class, false));
    }

    @Test
    void aRetypeKeepsTheChoicesThatStillReadAsTheNewType() {
        // An int's choices are no String's: "1" is not a string literal.
        assertEquals(List.of(), ChoiceMode.kept(GRAMMAR, String.class, List.of("1", "5")));
        // Ticking them keeps them: each is still a value of the list's element.
        assertEquals(List.of("1", "5"),
                ChoiceMode.kept(GRAMMAR, ValueTypes.listOf(Integer.class), List.of("1", "5")));
        assertEquals(List.of("5"), ChoiceMode.kept(GRAMMAR, int.class, List.of("\"a\"", "5")));
    }
}
