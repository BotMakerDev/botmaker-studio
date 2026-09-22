package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot's own record as the type of a parameter: what makes one editable, and what a value of it is written
 * and read as.
 *
 * <p>Source in, answers out — no project on disk — for the reason {@link JavaParameterSource} is tested that
 * way: what goes wrong here is the reading, and a project fixture would only make it slower to find.
 */
class BotRecordsTest {

    private static final String POINT = """
            package com.example.bot;

            public record Point(int x, int y) {}
            """;

    private static final String PARAMETERS_HEAD = """
            package com.example.bot;

            import com.botmaker.plugin.basics.params.Param;

            public final class Parameters {
            """;

    private static String parameters(String fields) {
        return PARAMETERS_HEAD + fields + "}\n";
    }

    private static BotRecords records(String... sources) {
        return BotRecords.of(TestValues.GRAMMAR, List.of(sources));
    }

    private static JavaParameter only(BotRecords records, String fields) {
        List<JavaParameter> found = JavaParameterSource.read(
                null, parameters(fields), TestValues.GRAMMAR, records);
        assertEquals(1, found.size());
        return found.getFirst();
    }

    private static ValueForm.Declared point() {
        return new ValueForm.Declared("com.example.bot.Point", List.of());
    }

    @Test
    void aFieldTypedWithTheBotsOwnRecordIsADeclaredFormAndNotAnUnknownLeaf() {
        JavaParameter origin = only(records(POINT), """
                    @Param
                    public static Point origin = new Point(1, 2);
                """);

        assertEquals(point(), origin.form());
        assertTrue(origin.editable(), origin.note());
        assertEquals("new Point(1, 2)", origin.row().value());
    }

    /** Without the bot's other sources the same field is exactly what it was before: an unknown leaf. */
    @Test
    void thePureReaderStillKnowsNothingAboutTheProject() {
        JavaParameter origin = only(BotRecords.none(), """
                    @Param
                    public static Point origin = new Point(1, 2);
                """);

        assertEquals(ValueForm.of("Point"), origin.form());
        assertFalse(origin.editable());
    }

    @Test
    void aValueIsTakenApartPositionallyAndPutBackFullyQualified() {
        BotRecords records = records(POINT);

        List<ValueGrammar.Part> parts = records.partsOf(point(), "new Point(1, 2)").orElseThrow();

        assertEquals(List.of("1", "2"), parts.stream().map(ValueGrammar.Part::initializer).toList());
        assertEquals(TestValues.WHOLE_NUMBER, parts.getFirst().form());
        assertEquals("new com.example.bot.Point(3, 4)",
                records.initializerOfParts(point(), List.of("3", "4")).orElseThrow());
    }

    /** A real parser, not a comma count: an argument may contain commas of its own. */
    @Test
    void anArgumentWithCommasInItIsStillOneArgument() {
        List<ValueGrammar.Part> parts = records(POINT)
                .partsOf(point(), "new Point(Math.max(1, 2), 3)").orElseThrow();

        assertEquals(List.of("Math.max(1, 2)", "3"), parts.stream()
                .map(ValueGrammar.Part::initializer).toList());
    }

    /**
     * Anything that is not this record's canonical constructor is left alone. A factory call, a constructor
     * of a different arity, a different class: each is a value somebody wrote deliberately, and a partial
     * reading of one would rewrite it.
     */
    @Test
    void onlyTheCanonicalConstructorReads() {
        BotRecords records = records(POINT);

        assertTrue(records.partsOf(point(), "Point.origin()").isEmpty());
        assertTrue(records.partsOf(point(), "new Point(1)").isEmpty());
        assertTrue(records.partsOf(point(), "new Size(1, 2)").isEmpty());
        assertTrue(records.initializerOfParts(point(), List.of("1")).isEmpty());
        assertTrue(records.initializerOfParts(point(), List.of("1", "")).isEmpty());
    }

    @Test
    void aHandWrittenValueOfARecordIsListedShownAndKept() {
        JavaParameter origin = only(records(POINT), """
                    @Param
                    public static Point origin = Point.origin();
                """);

        assertFalse(origin.editable());
        assertEquals("written by hand as Point.origin(), which is kept rather than replaced", origin.note());
        assertEquals("Point.origin()", origin.row().value());
    }

    @Test
    void anOrdinaryClassIsNotOfferedBecauseItsConstructorIsAGuess() {
        String box = """
                package com.example.bot;

                public final class Box {
                    public Box(int a) {}
                    public Box(int a, int b) {}
                }
                """;

        JavaParameter parameter = only(records(box), """
                    @Param
                    public static Box box = new Box(1);
                """);

        assertFalse(parameter.editable());
        assertTrue(parameter.note().contains("only a record"), parameter.note());
    }

    @Test
    void aComponentNoPluginRegistersNamesItself() {
        String shape = """
                package com.example.bot;

                public record Shape(int sides, Channel channel) {}
                """;

        String why = records(shape)
                .whyNotEditable(new ValueForm.Declared("com.example.bot.Shape", List.of()));

        assertNotNull(why);
        assertTrue(why.contains("Shape.channel"), why);
        assertTrue(why.contains("Channel"), why);
    }

    /** A record of a record reads all the way down, and each one is edited where it is declared. */
    @Test
    void aRecordOverAnotherRecordIsEditable() {
        String line = """
                package com.example.bot;

                public record Line(Point from, Point to) {}
                """;
        BotRecords records = records(POINT, line);
        ValueForm.Declared declared = new ValueForm.Declared("com.example.bot.Line", List.of());

        assertNull(records.whyNotEditable(declared));
        assertEquals(List.of("new Point(0, 0)", "new Point(1, 1)"),
                records.partsOf(declared, "new Line(new Point(0, 0), new Point(1, 1))").orElseThrow()
                        .stream().map(ValueGrammar.Part::initializer).toList());
    }

    /** A record that contains itself has no value to write, and says that rather than being walked. */
    @Test
    void aRecordThatContainsItselfIsRefusedWithThatAsTheReason() {
        String node = """
                package com.example.bot;

                public record Node(int value, Node next) {}
                """;

        String why = records(node).whyNotEditable(new ValueForm.Declared("com.example.bot.Node", List.of()));

        assertNotNull(why);
        assertTrue(why.contains("contains itself"), why);
    }

    /** A generic record is read and listed; its components are written in terms of a type variable. */
    @Test
    void aGenericRecordIsListedAndNotOffered() {
        String holder = """
                package com.example.bot;

                public record Holder<T>(T value) {}
                """;
        BotRecords records = records(holder);
        ValueForm.Declared declared = new ValueForm.Declared("com.example.bot.Holder",
                List.of(TestValues.WHOLE_NUMBER));

        assertNotNull(records.whyNotEditable(declared));
        assertTrue(records.whyNotEditable(declared).contains("type arguments"));
    }
}
