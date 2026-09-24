package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.TestValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code read(write(v)) == v}</b> — the whole claim of {@code docs/refactor/33-plugin-java.md}, asserted
 * over the shapes that break a reader written any other way.
 *
 * <p>A plugin's value used to be JSON, and moving it into the bot's Java is worth nothing unless the trip
 * out and back is a fixed point. Each case here is a way the previous encodings lost: a comma inside a
 * string (a reader that splits on commas), an empty list (a reader that cannot tell "no parts" from "not a
 * list"), a method reference (a value that is a <em>name</em> rather than data), and four levels of nesting
 * (a shape the old {@code ValueChoice} could not even say).
 *
 * <p>The fifth case is the one that must <em>not</em> round-trip: a body somebody wrote by hand comes back
 * read-only with a sentence, never as a partial value. A window that read half of a hand-written body and
 * then wrote it back would silently delete the author's code, which is the failure this rule exists to make
 * impossible.
 */
class JavaManagedRoundTripTest {

    private static final ValueGrammar CATALOG = TestValues.GRAMMAR;

    private static final Type TEXT = TestValues.TEXT;
    private static final Type DURATION = TestValues.DURATION;
    private static final Type BODY = TestValues.BODY;
    private static final Type TEXTS = ValueTypes.listOf(TEXT);
    /** {@code Map<String, List<Map<String, List<Duration>>>>} — four containers deep. */
    private static final Type DEEP = ValueTypes.mapOf(TEXT,
            ValueTypes.listOf(ValueTypes.mapOf(TEXT, ValueTypes.listOf(DURATION))));

    /** The file a plugin ships, with one {@code @Managed} method whose return type is spelled out. */
    private static String fileReturning(String returnType, String expression) {
        return """
                package com.example.bot.plugins.sdk;

                import com.botmaker.plugin.basics.managed.Managed;

                /** The plugin's values for this bot. Yours from the moment it was copied here. */
                public final class Sdk {

                    /** Called once, from main. */
                    public static void install() {
                        Plugin.use(value());
                    }

                    @Managed("flow")
                    public static %s value() {
                        return %s;
                    }

                    private Sdk() {}
                }
                """.formatted(returnType, expression);
    }

    /** Writes {@code value} into the file, reads the method back, and answers what came out. */
    private static Object roundTrip(String returnType, Type form, Object value) {
        JavaValue expression = CATALOG.initializer(form, value).orElseThrow();
        String written = JavaManagedEdits.setValue(fileReturning(returnType, "null"), "Sdk", "value", expression);
        ManagedMethod read = only(written);
        assertTrue(read.editable(), read.note());
        return CATALOG.valueOf(read.form(), read.expression()).orElseThrow();
    }

    private static ManagedMethod only(String source) {
        List<ManagedMethod> read = JavaManagedSource.read(null, source, CATALOG);
        assertEquals(1, read.size(), source);
        return read.getFirst();
    }

    // ---- the round trip ---------------------------------------------------------------------------------

    @Test
    void aCommaInsideAStringSurvives() {
        assertEquals("Collect, then battle", roundTrip("String", TEXT, "Collect, then battle"));
    }

    @Test
    void anEmptyListSurvivesAsAnEmptyList() {
        assertEquals(List.of(), roundTrip("java.util.List<String>", TEXTS, List.of()));
    }

    /** A type a plugin declares and cannot take apart crosses as the source it is written as. */
    @Test
    void aMethodReferenceSurvives() {
        assertEquals("Collect::body",
                roundTrip("com.botmaker.studio.project.params.TestValues.Body", BODY, "Collect::body"));
    }

    @Test
    void fourLevelsOfNestingSurvive() {
        Object value = Map.of("phase one",
                List.of(Map.of("rests", List.of(Duration.ofMillis(250), Duration.ofSeconds(3)))));
        assertEquals(value, roundTrip(
                "java.util.Map<String, java.util.List<java.util.Map<String, java.util.List<java.time.Duration>>>>",
                DEEP, value));
    }

    /**
     * A record with fixed components, written as a plain type name and read back as the shape it is.
     *
     * <p>This is the SDK's {@code Flow} in miniature: a {@code ComponentType} nothing declares on its own,
     * which the plugin hands over through {@code componentTypes()} so the host can read it at all.
     */
    @Test
    void aFixedShapeWrittenAsAPlainTypeNameRoundTrips() {
        TestValues.Span value = new TestValues.Span("phase one", 12);

        Object read = roundTrip("com.botmaker.studio.project.params.TestValues.Span", TestValues.SPAN_FORM, value);

        assertEquals(value, read);
        assertEquals("TestValues.Span", ValueTypes.sourceName(TestValues.SPAN_FORM));
        assertEquals("com.botmaker.studio.project.params.TestValues.Span.of(\"phase one\", 12)",
                CATALOG.initializer(TestValues.SPAN_FORM, value).orElseThrow().source());
    }

    // ---- what must not round-trip -----------------------------------------------------------------------

    @Test
    void aHandEditedBodyIsReadOnlyAndNotPartlyRead() {
        String source = """
                package com.example.bot.plugins.sdk;
                import com.botmaker.plugin.basics.managed.Managed;
                public final class Sdk {
                    @Managed("flow")
                    public static String value() {
                        String base = "Collect";
                        return base + " and battle";
                    }
                }
                """;

        ManagedMethod read = only(source);
        assertFalse(read.editable());
        assertTrue(read.note().contains("not a single return"), read.note());
        // Nothing partial: the expression is empty rather than the last statement's text, so no caller can
        // mistake half a body for the value.
        assertEquals("", read.expression());
        // And the writer declines it, so a window that ignored the note still could not overwrite the code.
        assertSame(source, JavaManagedEdits.setValue(source, "Sdk", "value", null));
        assertEquals(source, JavaManagedEdits.setValue(source, "Sdk", "value", java("\"x\"")));
    }

    /** Java as a value to write, kept as written and naming {@code imports}. */
    private static JavaValue java(String source, String... imports) {
        return new JavaValue(JavaValue.parse(source).orElseThrow().node(), List.of(imports), source);
    }

    @Test
    void aTypeNothingDeclaresIsReadOnlyAndKept() {
        ManagedMethod read = only(fileReturning("com.example.bot.Mystery", "Mystery.of(1)"));

        assertFalse(read.editable());
        assertTrue(read.note().contains("no installed plugin declares"), read.note());
        assertEquals("Mystery.of(1)", read.expression(), "still shown, exactly as written");
    }

    // ---- what the rewrite leaves alone ------------------------------------------------------------------

    @Test
    void everythingButTheExpressionIsUntouched() {
        String before = fileReturning("java.time.Duration", "java.time.Duration.ofMillis(1L)");
        // Written fully qualified, so it needs no import and the file gains no line.
        String after = JavaManagedEdits.setValue(before, "Sdk", "value", java("java.time.Duration.ofMillis(3000L)"));

        assertTrue(after.contains("java.time.Duration.ofMillis(3000L)"), after);
        assertTrue(after.contains("/** The plugin's values for this bot."), "the javadoc is still there");
        assertTrue(after.contains("Plugin.use(value());"), "install() is untouched");
        assertTrue(after.contains("private Sdk() {}"), "the constructor is untouched");
        assertEquals(before.lines().count(), after.lines().count(), after);
    }

    @Test
    void anImportTheExpressionNeedsIsAddedOnceAndOnlyWhenMissing() {
        String source = """
                package com.example.bot.plugins.sdk;

                import com.botmaker.plugin.basics.managed.Managed;

                public final class Sdk {

                    @Managed("flow")
                    public static java.util.List<String> value() {
                        return null;
                    }
                }
                """;

        String once = JavaManagedEdits.setValue(source, "Sdk", "value", java("List.of(\"a\")", "java.util.List"));
        assertEquals(1, count(once, "import java.util.List;"), once);

        String twice = JavaManagedEdits.setValue(once, "Sdk", "value", java("List.of(\"b\")", "java.util.List"));
        assertEquals(1, count(twice, "import java.util.List;"), twice);
    }

    @Test
    void aMethodTheFileDoesNotHaveChangesNothing() {
        String source = fileReturning("String", "\"a\"");

        assertSame(source, JavaManagedEdits.setValue(source, "Sdk", "missing", java("\"b\"")));
        assertSame(source, JavaManagedEdits.setValue(source, "Other", "value", java("\"b\"")));
    }

    // ---- what is found at all ---------------------------------------------------------------------------

    @Test
    void onlyAnAnnotatedMethodIsAValue() {
        List<ManagedMethod> read = JavaManagedSource.read(null, """
                package com.example.bot;
                import com.botmaker.plugin.basics.managed.Managed;
                public final class Sdk {
                    @Managed("flow")
                    public static String flow() { return "a"; }
                    public static String helper() { return "b"; }
                    @Managed
                    public static String noId() { return "c"; }
                }
                """, CATALOG);

        assertEquals(List.of("flow"), read.stream().map(ManagedMethod::id).toList());
        assertEquals("Sdk.flow()", read.getFirst().qualified());
    }

    @Test
    void aMethodThatIsNotPublicStaticAndNullaryIsReadOnlyWithItsOwnSentence() {
        assertTrue(note("static String value() { return \"a\"; }").contains("not public"));
        assertTrue(note("public String value() { return \"a\"; }").contains("not static"));
        assertTrue(note("public static String value(int n) { return \"a\"; }")
                .contains("takes parameters"));
    }

    private static String note(String declaration) {
        return only("""
                package com.example.bot;
                import com.botmaker.plugin.basics.managed.Managed;
                public final class Sdk {
                    @Managed("flow")
                    %s
                }
                """.formatted(declaration)).note();
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }
}
