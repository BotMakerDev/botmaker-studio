package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.project.params.TestValues;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Executable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one grammar: a form directs the write, the same form directs the read, and a live value is what
 * crosses in both directions.
 *
 * <p>Three rules are what these tests are for, and they matter more with nesting rather than less — empty
 * means decline, a partial reading is not a reading, and the split is not a parser.
 *
 * <p>Since 2026-09-22 the leaves are declared the way a plugin declares them — a {@link PluginType}, and a
 * {@link ComponentType} beside it where the Java is a call — and nothing here has a codec: the host spells
 * every character itself.
 */
class ValueGrammarTest {

    /** {@code new java.awt.Color(r, g, b)} — the value that was rewritten on open, and the reason for all this. */
    static final class ColorType implements PluginType<Color>, ComponentType<Color> {
        @Override public Class<Color> type() { return Color.class; }
        @Override public Color fresh() { return Color.WHITE; }
        @Override public Node editor(ValueContext ctx) { return null; }
        @Override public List<Class<?>> componentTypes() { return List.of(int.class, int.class, int.class); }
        @Override public List<Object> components(Color c) { return List.of(c.getRed(), c.getGreen(), c.getBlue()); }
        @Override public Color build(List<Object> parts) {
            return new Color((int) parts.get(0), (int) parts.get(1), (int) parts.get(2));
        }
    }

    /** {@code java.time.Duration.ofMillis(long)}. */
    static final class DurationType implements PluginType<Duration>, ComponentType<Duration> {
        @Override public Class<Duration> type() { return Duration.class; }
        @Override public Duration fresh() { return Duration.ZERO; }
        @Override public Node editor(ValueContext ctx) { return null; }
        @Override public Executable factory() { return TestValues.method(Duration.class, "ofMillis", long.class); }
        @Override public List<Class<?>> componentTypes() { return List.of(long.class); }
        @Override public List<Object> components(Duration d) { return List.of(d.toMillis()); }
        @Override public Duration build(List<Object> parts) { return Duration.ofMillis((long) parts.getFirst()); }
    }

    /** An enum a plugin declares: nothing to take apart, written as its constant. */
    static final class UnitType implements PluginType<ChronoUnit> {
        @Override public Class<ChronoUnit> type() { return ChronoUnit.class; }
        @Override public ChronoUnit fresh() { return ChronoUnit.SECONDS; }
        @Override public Node editor(ValueContext ctx) { return null; }
    }

    /** A value the bot evaluates: declared, never taken apart, starting as a call. */
    record Source(String written) {
        public static Source current() { return new Source(""); }
    }

    static final class SourceType implements PluginType<Source> {
        @Override public Class<Source> type() { return Source.class; }
        @Override public Source fresh() { return null; }
        @Override public java.lang.reflect.Method freshCall() {
            try {
                return Source.class.getMethod("current");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
        @Override public Node editor(ValueContext ctx) { return null; }
    }

    /** A flow in miniature: parts written as calls inside one call, one of them a name kept as source. */
    public record Step(String body, String name, List<String> outcomes) {}

    public record Plan(List<Step> steps, String start) {
        public static Plan of(List<Step> steps, String start) { return new Plan(steps, start); }
        public static Step step(Body body, String name, List<String> outcomes) {
            return new Step(String.valueOf(body), name, outcomes);
        }
    }

    /** The part a step's body is: nothing declares it, so it crosses as the source it is written as. */
    public interface Body {}

    static final ComponentType<Step> STEP = new ComponentType<>() {
        @Override public Class<Step> type() { return Step.class; }
        @Override public Executable factory() {
            return TestValues.method(Plan.class, "step", Body.class, String.class, List.class);
        }
        @Override public List<Class<?>> componentTypes() { return List.of(Body.class, String.class, List.class); }
        @Override public List<Object> components(Step s) { return List.of(s.body(), s.name(), s.outcomes()); }
        @SuppressWarnings("unchecked")
        @Override public Step build(List<Object> parts) {
            return new Step((String) parts.get(0), (String) parts.get(1), (List<String>) parts.get(2));
        }
    };

    static final ComponentType<Plan> PLAN = new ComponentType<>() {
        @Override public Class<Plan> type() { return Plan.class; }
        @Override public Executable factory() { return TestValues.method(Plan.class, "of", List.class, String.class); }
        @Override public List<Class<?>> componentTypes() { return List.of(List.class, String.class); }
        @Override public List<Object> components(Plan p) { return List.of(p.steps(), p.start()); }
        @SuppressWarnings("unchecked")
        @Override public Plan build(List<Object> parts) {
            return new Plan((List<Step>) parts.get(0), (String) parts.get(1));
        }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(
            List.of(new ColorType(), new DurationType(), new UnitType(), new SourceType()),
            List.of(STEP, PLAN));

    private static final ValueForm TEXT = ValueForm.of(String.class);
    private static final ValueForm COUNT = ValueForm.of(int.class);
    private static final ValueForm DURATION = ValueForm.of(Duration.class);
    private static final ValueForm COLOR = ValueForm.of(Color.class);

    // ---- the bug this design exists to fix --------------------------------------------------------------

    /**
     * A colour opened and closed with no edit comes back <b>byte-identical</b>. It used to be read through a
     * codec as text it could not parse and written back as {@code new java.awt.Color(255, 255, 255)}.
     */
    @Test
    void aColourReadAndWrittenBackIsTheSameJava() {
        String written = "new java.awt.Color(12, 200, 7)";
        Color read = (Color) GRAMMAR.valueOf(COLOR, written).orElseThrow();
        assertEquals(new Color(12, 200, 7), read);
        assertEquals(written, GRAMMAR.initializer(COLOR, read).orElseThrow());
    }

    @Test
    void aDurationReadAndWrittenBackIsTheSameJava() {
        String written = "java.time.Duration.ofMillis(60000L)";
        assertEquals(written, GRAMMAR.initializer(DURATION, GRAMMAR.valueOf(DURATION, written).orElseThrow())
                .orElseThrow());
    }

    // ---- writing -------------------------------------------------------------------------------------------

    @Test
    void aJdkLeafIsItsOwnLiteral() {
        assertEquals(Optional.of("\"hel\\\"lo\""), GRAMMAR.initializer(TEXT, "hel\"lo"));
        assertEquals(Optional.of("3"), GRAMMAR.initializer(COUNT, 3));
        assertEquals(Optional.of("3L"), GRAMMAR.initializer(ValueForm.of(long.class), 3));
        assertEquals(Optional.of("2.5"), GRAMMAR.initializer(ValueForm.of(double.class), 2.5));
        assertEquals(Optional.of("'\\n'"), GRAMMAR.initializer(ValueForm.of(char.class), '\n'));
    }

    @Test
    void aNumberThatWouldLoseSomethingIsRefused() {
        assertTrue(GRAMMAR.initializer(COUNT, 2.5).isEmpty(), "2.5 is not an int");
        assertTrue(GRAMMAR.initializer(COUNT, "3").isEmpty(), "a string is not an int");
    }

    @Test
    void anEnumIsItsConstant() {
        ValueForm unit = ValueForm.of(ChronoUnit.class);
        assertEquals(Optional.of("java.time.temporal.ChronoUnit.MINUTES"), GRAMMAR.initializer(unit, ChronoUnit.MINUTES));
        assertEquals(Optional.of(ChronoUnit.MINUTES), GRAMMAR.valueOf(unit, "ChronoUnit.MINUTES"));
        // A static import writes the constant alone, and the type is known, so it reads.
        assertEquals(Optional.of(ChronoUnit.MINUTES), GRAMMAR.valueOf(unit, "MINUTES"));
        assertTrue(GRAMMAR.valueOf(unit, "Other.MINUTES").isEmpty(), "a different owner is a different type");
    }

    @Test
    void aListIsTheFactoryCall() {
        assertEquals(Optional.of("java.util.List.of(\"a\", \"b\")"),
                GRAMMAR.initializer(ValueForm.listOf(TEXT), List.of("a", "b")));
        assertEquals(Optional.of("java.util.List.of()"), GRAMMAR.initializer(ValueForm.listOf(TEXT), List.of()));
    }

    @Test
    void aMapIsAlwaysOfEntriesAndNeverMapOf() {
        // One spelling means one rule each side and no behaviour change at the eleventh entry.
        Map<String, Integer> value = new LinkedHashMap<>();
        value.put("a", 1);
        value.put("b", 2);
        assertEquals(Optional.of("java.util.Map.ofEntries("
                        + "java.util.Map.entry(\"a\", 1), java.util.Map.entry(\"b\", 2))"),
                GRAMMAR.initializer(ValueForm.mapOf(TEXT, COUNT), value));
    }

    @Test
    void anUnknownTypeDeclinesRatherThanGuesses() {
        // A guess compiles into a user's bot, which is worse than showing them source nobody can edit.
        assertTrue(GRAMMAR.initializer(ValueForm.of("discord.Channel"), "x").isEmpty());
        assertTrue(GRAMMAR.initializer(ValueForm.listOf(ValueForm.of("discord.Channel")), List.of("x")).isEmpty());
    }

    @Test
    void aClassTheBotDeclaresIsNotTheGrammarsToWrite() {
        assertTrue(GRAMMAR.initializer(new ValueForm.Declared("com.mybot.Box", List.of()), "x").isEmpty());
    }

    /** What a slot in a user's own file is given: simple names, and the imports that makes necessary. */
    @Test
    void aSpellingForAPersonsFileUsesSimpleNamesAndSaysWhatToImport() {
        ValueGrammar.Written written = GRAMMAR.spell(ValueForm.listOf(COLOR), List.of(Color.RED)).orElseThrow();
        assertEquals("List.of(new Color(255, 0, 0))", written.source());
        assertEquals(java.util.Set.of("java.util.List", "java.awt.Color"), java.util.Set.copyOf(written.imports()));
    }

    // ---- a type declared and never taken apart ---------------------------------------------------------

    @Test
    void aDeclaredTypeWithNoReaderCrossesAsItsSource() {
        ValueForm source = ValueForm.of(Source.class);
        assertEquals(Optional.of("CaptureSource.desktop()"), GRAMMAR.valueOf(source, "CaptureSource.desktop()"));
        assertEquals(Optional.of("CaptureSource.desktop()"), GRAMMAR.initializer(source, "CaptureSource.desktop()"));
        assertTrue(GRAMMAR.known(source));
    }

    /** A fresh call of the wrong shape is never written: it would not compile in the bot's file. */
    @Test
    void aFreshCallOfTheWrongShapeWritesNothing() {
        PluginType<Source> takesAnArgument = new PluginType<>() {
            @Override public Class<Source> type() { return Source.class; }
            @Override public Source fresh() { return null; }
            @Override public java.lang.reflect.Method freshCall() {
                try {
                    return String.class.getMethod("valueOf", Object.class);
                } catch (NoSuchMethodException e) {
                    throw new AssertionError(e);
                }
            }
            @Override public Node editor(ValueContext ctx) { return null; }
        };
        ValueGrammar grammar = ValueGrammar.of(List.of(takesAnArgument), List.of());

        assertTrue(grammar.freshInitializer(ValueForm.of(Source.class)).isEmpty());
    }

    @Test
    void aFreshValueIsTheDeclarationsOwnWrittenOrItsStartingCall() {
        assertEquals(Optional.of("new java.awt.Color(255, 255, 255)"), GRAMMAR.freshInitializer(COLOR));
        assertEquals(Optional.of(Source.class.getCanonicalName() + ".current()"),
                GRAMMAR.freshInitializer(ValueForm.of(Source.class)));
        ValueGrammar.Written spelled = GRAMMAR.freshSpelling(ValueForm.of(Source.class)).orElseThrow();
        assertEquals("ValueGrammarTest.Source.current()", spelled.source(), "a fresh call is written by the host");
        assertEquals(List.of(ValueGrammarTest.class.getName()), spelled.imports());
        assertEquals(Optional.of("java.util.List.of()"), GRAMMAR.freshInitializer(ValueForm.listOf(COLOR)));
        assertTrue(GRAMMAR.freshInitializer(ValueForm.of("discord.Channel")).isEmpty(), "nothing invented");
        assertTrue(GRAMMAR.freshInitializer(new ValueForm.Declared("com.mybot.Box", List.of())).isEmpty());
    }

    // ---- composites a plugin declares --------------------------------------------------------------------

    /**
     * The SDK's {@code Flow}, in miniature: a call whose parts are a list of calls, strings, and a name kept
     * as the source it is written as. A list component says only {@code List.class}, so its elements are
     * read by their own spelling — a call by its prefix, a literal by its kind.
     */
    @Test
    void aPlanOfStepsRoundTripsThroughItsOwnComponents() {
        String written = "Plan.of(List.of(Plan.step(Collect::body, \"Collect\", List.of(\"DONE\")), "
                + "Plan.step(Rest::body, \"Rest\", List.of())), \"Collect\")";
        ValueForm plan = ValueForm.of(Plan.class);

        Plan read = (Plan) GRAMMAR.valueOf(plan, written).orElseThrow();

        assertEquals(new Plan(List.of(new Step("Collect::body", "Collect", List.of("DONE")),
                new Step("Rest::body", "Rest", List.of())), "Collect"), read);
        ValueGrammar.Written spelled = GRAMMAR.spell(plan, read).orElseThrow();
        assertEquals("ValueGrammarTest.Plan.of(List.of(ValueGrammarTest.Plan.step(Collect::body, \"Collect\", "
                + "List.of(\"DONE\")), ValueGrammarTest.Plan.step(Rest::body, \"Rest\", List.of())), \"Collect\")",
                spelled.source());
        assertEquals(Optional.of(read), GRAMMAR.valueOf(plan, spelled.source()));
    }

    @Test
    void aNameThatWouldBeEmptyDeclinesTheWholeValue() {
        // A part kept as source has no Java when it is blank; writing `Plan.step(, …)` would not compile.
        Plan plan = new Plan(List.of(new Step("", "Collect", List.of())), "Collect");
        assertTrue(GRAMMAR.initializer(ValueForm.of(Plan.class), plan).isEmpty());
    }

    @Test
    void anUntypedValueIsReadAndWrittenByItsOwnSpelling() {
        assertEquals(Optional.of("x"), GRAMMAR.valueOfAny("\"x\""));
        assertEquals(Optional.of(List.of(1, 2L)), GRAMMAR.valueOfAny("List.of(1, 2L)"));
        assertEquals(Optional.of(new Color(1, 2, 3)), GRAMMAR.valueOfAny("new java.awt.Color(1, 2, 3)"));
        assertEquals(Optional.of("new java.awt.Color(1, 2, 3)"), GRAMMAR.initializerOfAny(new Color(1, 2, 3)));
    }

    // ---- reading -------------------------------------------------------------------------------------------

    @Test
    void everyFormRoundTrips() {
        Map<String, List<Integer>> nested = new LinkedHashMap<>();
        nested.put("a", List.of(1, 2));
        nested.put("b", List.of());

        for (Object[] pair : new Object[][]{
                {TEXT, "hello"},
                {COLOR, Color.ORANGE},
                {ValueForm.listOf(TEXT), List.of("a", "b")},
                {ValueForm.listOf(ValueForm.listOf(COUNT)), List.of(List.of(1), List.of(2, 3))},
                {ValueForm.mapOf(TEXT, ValueForm.listOf(COUNT)), nested},
                {ValueForm.mapOf(TEXT, DURATION), Map.of("rest", Duration.ofSeconds(3))}}) {
            ValueForm form = (ValueForm) pair[0];
            Object value = pair[1];
            String written = GRAMMAR.initializer(form, value).orElseThrow();
            assertEquals(Optional.of(value), GRAMMAR.valueOf(form, written), written);
        }
    }

    @Test
    void aShorterSpellingReadsToo() {
        // A user's own file, having imported List, writes it shorter than the grammar does.
        assertEquals(Optional.of(List.of("a")), GRAMMAR.valueOf(ValueForm.listOf(TEXT), "List.of(\"a\")"));
        assertEquals(Optional.of(Map.of("a", 1)),
                GRAMMAR.valueOf(ValueForm.mapOf(TEXT, COUNT), "Map.ofEntries(Map.entry(\"a\", 1))"));
        assertEquals(Optional.of(Color.RED), GRAMMAR.valueOf(COLOR, "new Color(255, 0, 0)"));
        assertEquals(Optional.of(Duration.ofMillis(5)), GRAMMAR.valueOf(DURATION, "Duration.ofMillis(5)"));
    }

    @Test
    void onePartNothingReadsEmptiesTheWholeAnswer() {
        // With nesting this matters more, not less: a map with one unreadable value is shown whole and
        // untouched rather than silently losing an entry.
        ValueForm form = ValueForm.mapOf(TEXT, DURATION);
        assertTrue(GRAMMAR.valueOf(form, "java.util.Map.ofEntries(java.util.Map.entry(\"a\", "
                + "java.time.Duration.ofSeconds(3)))").isEmpty());
    }

    @Test
    void aSourceThisGrammarDidNotWriteAnswersEmpty() {
        ValueForm list = ValueForm.listOf(TEXT);
        assertTrue(GRAMMAR.valueOf(list, "new ArrayList<>()").isEmpty());
        assertTrue(GRAMMAR.valueOf(list, "java.util.List.of(\"a\"").isEmpty(), "unbalanced");
        assertTrue(GRAMMAR.valueOf(list, "java.util.Set.of(\"a\")").isEmpty());
        // A map read as a list is not a partial success either.
        assertTrue(GRAMMAR.valueOf(list, "java.util.Map.ofEntries()").isEmpty());
        assertTrue(GRAMMAR.valueOf(COLOR, "Color.NOPE").isEmpty(), "a constant the class does not declare");
        assertTrue(GRAMMAR.valueOf(COLOR, "Colour.RED").isEmpty(), "a class nothing declares");
        assertTrue(GRAMMAR.valueOf(COUNT, "1 + 2").isEmpty(), "an expression is not a literal");
    }

    @Test
    void aCommaInsideALiteralIsNotASplit() {
        assertEquals(Optional.of(List.of("a, b", "c")),
                GRAMMAR.valueOf(ValueForm.listOf(TEXT), "java.util.List.of(\"a, b\", \"c\")"));
    }

    @Test
    void jdkLiteralsAreReadAsJavacReadsThem() {
        assertEquals(Optional.of(31), GRAMMAR.valueOf(COUNT, "0x1F"));
        assertEquals(Optional.of(1000), GRAMMAR.valueOf(COUNT, "1_000"));
        assertEquals(Optional.of(-4), GRAMMAR.valueOf(COUNT, "-4"));
        assertEquals(Optional.of(5L), GRAMMAR.valueOf(ValueForm.of(long.class), "5"));
        assertTrue(GRAMMAR.valueOf(COUNT, "3000000000L").isEmpty(), "a wider literal is refused, never truncated");
        assertEquals(Optional.of("a\"b\n"), GRAMMAR.valueOf(TEXT, "\"a\\\"b\\n\""));
        assertEquals(Optional.of('x'), GRAMMAR.valueOf(ValueForm.of(char.class), "'x'"));
        assertEquals(Optional.of(true), GRAMMAR.valueOf(ValueForm.of(boolean.class), "true"));
    }

    // ---- what an editor asks, which is one level at a time --------------------------------------------------

    @Test
    void aCompositeIsTakenApartOneLevelWithEachPartsFormBesideIt() {
        ValueForm map = ValueForm.mapOf(TEXT, COUNT);

        List<ValueGrammar.Part> entries = GRAMMAR
                .partsOfInitializer(map, "java.util.Map.ofEntries("
                        + "java.util.Map.entry(\"a\", 1), java.util.Map.entry(\"b\", 2))")
                .orElseThrow();

        assertEquals(2, entries.size());
        assertEquals("java.util.Map.entry(\"a\", 1)", entries.getFirst().source());
        // A map's parts are entries, so a cell reaches the key and the value by asking again.
        List<ValueGrammar.Part> pair = GRAMMAR
                .partsOfInitializer(entries.getFirst().form(), entries.getFirst().written())
                .orElseThrow();
        assertEquals(List.of("\"a\"", "1"), pair.stream().map(ValueGrammar.Part::source).toList());
        assertEquals(TEXT, pair.getFirst().form());
        assertEquals(COUNT, pair.get(1).form());
    }

    /** A part nothing reads is still a part: the cell shows it and refuses to rewrite it. */
    @Test
    void aPartNothingReadsIsStillAPart() {
        List<ValueGrammar.Part> parts = GRAMMAR
                .partsOfInitializer(ValueForm.listOf(DURATION), "java.util.List.of(Duration.parse(\"5s\"))")
                .orElseThrow();

        assertEquals(List.of("Duration.parse(\"5s\")"), parts.stream().map(ValueGrammar.Part::source).toList());
        // The whole-value reader still declines, which is what keeps the file untouched.
        assertTrue(GRAMMAR.valueOf(ValueForm.listOf(DURATION), "java.util.List.of(Duration.parse(\"5s\"))").isEmpty());
    }

    @Test
    void partsAlreadyWrittenAreComposedBackIntoTheFactoryCall() {
        ValueForm list = ValueForm.listOf(TEXT);

        assertEquals(Optional.of("java.util.List.of(\"a\", \"b\")"),
                GRAMMAR.initializerOfParts(list, List.of("\"a\"", "\"b\"")));
        assertEquals(Optional.of("java.util.List.of()"), GRAMMAR.initializerOfParts(list, List.of()));
        // A blank part declines rather than writing `of(, "b")`.
        assertTrue(GRAMMAR.initializerOfParts(list, List.of("", "\"b\"")).isEmpty());
        // And a container that cannot hold that many parts declines too.
        assertTrue(GRAMMAR.initializerOfParts(new ValueForm.Of(ValueContainer.ENTRY, List.of(TEXT, COUNT)),
                List.of("\"a\"", "1", "2")).isEmpty());
    }

    // ---- handing a value to an editor ------------------------------------------------------------------

    @Test
    void aValueIsHandedOverOnlyAsTheClassAskedFor() {
        assertEquals(Optional.of(3), ValueGrammar.as(3, int.class), "a primitive asks for its box");
        assertEquals(Optional.of(3), ValueGrammar.as(3, Integer.class));
        assertTrue(ValueGrammar.as(3, String.class).isEmpty());
        assertTrue(ValueGrammar.as(null, String.class).isEmpty());
    }

    // ---- imports -------------------------------------------------------------------------------------------

    @Test
    void aDeclarationImportsItsLeavesAndNotItsContainers() {
        // A container is written fully qualified in a declaration; a leaf by its simple name.
        assertEquals(List.of("java.time.Duration"), GRAMMAR.imports(ValueForm.mapOf(DURATION, TEXT)));
        assertEquals(List.of(), GRAMMAR.imports(ValueForm.listOf(COUNT)));
        assertEquals(List.of(), GRAMMAR.imports(TEXT));
    }
}
