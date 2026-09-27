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
        assertEquals(List.of(ChoiceMode.ONE, ChoiceMode.MANY), ChoiceMode.offered(DayOfWeek.class, true));
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

    /** A grammar that declares an enum, which {@link TestValues#GRAMMAR} does not. */
    private static final ValueGrammar DAYS = ValueGrammar.of(List.of(TestValues.WHOLE_NUMBER_TYPE,
            new com.botmaker.plugin.api.value.PluginType<DayOfWeek>() {
                @Override public Class<DayOfWeek> type() { return DayOfWeek.class; }
                @Override public DayOfWeek fresh() { return DayOfWeek.MONDAY; }
            }), List.of());

    private static final List<String> WEEK = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
            "SATURDAY", "SUNDAY");

    /**
     * An enum is already a closed set, so "any value" says nothing about it (feedback 3): with no choices
     * written down it is one of every constant, and a list of it is any number of them.
     */
    @Test
    void anEnumIsAlwaysPickedFromItsConstants() {
        var days = ValueTypes.listOf(DayOfWeek.class);
        assertEquals(ChoiceMode.ONE, ChoiceMode.of(DayOfWeek.class, List.of(), DAYS));
        assertEquals(ChoiceMode.MANY, ChoiceMode.of(days, List.of(), DAYS));
        assertEquals(DayOfWeek.class, ChoiceMode.base(days, List.of(), DAYS));
        assertEquals(ChoiceMode.ONE, ChoiceMode.of(DayOfWeek.class, List.of("MONDAY", "FRIDAY"), DAYS));
        assertEquals(ChoiceMode.MANY, ChoiceMode.of(days, List.of("MONDAY", "FRIDAY"), DAYS));
        // An enum nothing declares is read by nobody, so it has no constants to pick from.
        assertEquals(ChoiceMode.NONE, ChoiceMode.of(DayOfWeek.class, List.of(), TestValues.GRAMMAR));
    }

    /** Every constant ticked means the same as no choices, so it is written as none. */
    @Test
    void everyConstantTickedIsWrittenAsNoChoices() {
        assertEquals(List.of(), ChoiceMode.enumOptions(DayOfWeek.class, WEEK));
        // Kept in the enum's own order, however they were ticked.
        assertEquals(List.of("MONDAY", "FRIDAY"), ChoiceMode.enumOptions(DayOfWeek.class, List.of("FRIDAY", "MONDAY")));
        assertEquals(WEEK, ChoiceMode.ticked(DayOfWeek.class, List.of()));
        assertEquals(List.of("MONDAY", "FRIDAY"), ChoiceMode.ticked(DayOfWeek.class, List.of("MONDAY", "FRIDAY")));
    }

    /** Seven days are a strip of toggles; thirty fields, like a hundred keys, are rows of their own picker. */
    @Test
    void onlyASmallEnumIsAStripOfToggles() {
        assertEquals(true, ChoiceMode.toggled(DayOfWeek.class));
        assertEquals(false, ChoiceMode.toggled(java.time.temporal.ChronoField.class));
        assertEquals(false, ChoiceMode.toggled(int.class));
    }

    /**
     * A retype keeps its mode where the mode still means something. An enum's unwritten "every constant" is
     * not a choice the user made, so it does not survive becoming an int; a free int becoming an enum is one
     * of its constants, since an enum has no free mode.
     */
    @Test
    void aRetypeKeepsTheModeWhereItStillMeansSomething() {
        assertEquals(ChoiceMode.NONE, ChoiceMode.afterRetype(ChoiceMode.ONE, false, int.class, true));
        assertEquals(ChoiceMode.ONE, ChoiceMode.afterRetype(ChoiceMode.ONE, true, int.class, true));
        assertEquals(ChoiceMode.ONE, ChoiceMode.afterRetype(ChoiceMode.NONE, false, DayOfWeek.class, true));
        assertEquals(ChoiceMode.MANY, ChoiceMode.afterRetype(ChoiceMode.MANY, false, java.time.Month.class, true));
        assertEquals(ChoiceMode.NONE,
                ChoiceMode.afterRetype(ChoiceMode.MANY, true, ValueTypes.listOf(int.class), true));
    }

    /**
     * A range limits what can be typed, and a set of choices is already the limit — so choices declared on a
     * ranged number drop the range, rather than leaving a bound nobody can see any more (feedback 3).
     */
    @Test
    void choicesDropTheRangeAndNoChoicesKeepIt() {
        ParameterRow ranged = ParameterRow.named("speed", "int").bounds(0, 10).build();

        var picked = ChoiceMode.declare(ranged, List.of("1", "5"));
        assertEquals(List.of("1", "5"), picked.options());
        assertEquals(Double.NEGATIVE_INFINITY, picked.min());
        assertEquals(Double.POSITIVE_INFINITY, picked.max());

        var free = ChoiceMode.declare(ranged, List.of());
        assertEquals(0.0, free.min());
        assertEquals(10.0, free.max());
    }
}
