package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.grammar.ValueForm;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading {@code @Param} fields out of source text.
 *
 * <p>Everything here is source in and rows out — no project, no filesystem, no host — which is the point of
 * {@link JavaParameterSource} being its own class. The grammar is {@link TestValues}', for the reason
 * written there: Studio depends on no plugin.
 */
class JavaParameterSourceTest {

    private static List<JavaParameter> read(String source) {
        return JavaParameterSource.read(null, source, TestValues.GRAMMAR);
    }

    private static String wrap(String fields) {
        return """
                package com.example.bot;

                import com.botmaker.plugin.basics.params.Param;
                import java.time.Duration;

                public final class Parameters {
                %s
                }
                """.formatted(fields);
    }

    @Test
    void aPlainParameterReadsAsItsNameTypeAndValue() {
        List<JavaParameter> found = read(wrap("""
                    @Param
                    public static int maxAttempts = 10;
                """));

        assertEquals(1, found.size());
        JavaParameter parameter = found.getFirst();
        assertEquals("Parameters", parameter.className());
        assertEquals("maxAttempts", parameter.name());
        assertEquals("Parameters.maxAttempts", parameter.qualified());
        assertEquals(TestValues.WHOLE_NUMBER, parameter.form().leaf());
        assertEquals("int", parameter.row().typeName());
        assertEquals("10", parameter.row().value());
        assertTrue(parameter.editable(), parameter.note());
    }

    @Test
    void everyAnnotationMemberIsRead() {
        JavaParameter parameter = read(wrap("""
                    @Param(category = "Limits", description = "How many times to try",
                            visibility = "public", min = 1, max = 50.5)
                    public static int maxAttempts = 10;
                """)).getFirst();

        ParameterRow row = parameter.row();
        assertEquals("Limits", row.category());
        assertEquals("How many times to try", row.description());
        assertEquals(Visibility.PUBLIC, row.visibility());
        assertEquals(1.0, row.min());
        assertEquals(50.5, row.max());
    }

    /** A bot written against plugin-basics' annotation wrote its bounds as text, and they still bound. */
    @Test
    void aBoundWrittenAsTextIsTheSameBound() {
        ParameterRow row = read(wrap("""
                    @Param(min = "1", max = "-2")
                    public static int a = 1;
                """)).getFirst().row();

        assertEquals(1.0, row.min());
        assertEquals(-2.0, row.max());
    }

    @Test
    void optionsAreReadAsAList() {
        JavaParameter parameter = read(wrap("""
                    @Param(options = {"fast", "slow"})
                    public static String speed = "fast";
                """)).getFirst();

        assertEquals(List.of("fast", "slow"), parameter.row().options());
        assertEquals("\"fast\"", parameter.row().value());
    }

    @Test
    void anUnadornedParameterIsInTheDefaultGroupAndNotPublic() {
        ParameterRow row = read(wrap("""
                    @Param
                    public static int a = 1;
                """)).getFirst().row();

        assertEquals("", row.category());
        assertEquals(Visibility.EDITOR_ONLY, row.visibility());
        assertFalse(row.isBounded());
        assertEquals(List.of(), row.options());
    }

    @Test
    void aTypeIsRecognisedWrittenSimplyOrFullyQualified() {
        List<JavaParameter> found = read(wrap("""
                    @Param
                    public static Duration simple = java.time.Duration.ofMillis(3000L);

                    @Param
                    public static java.time.Duration qualified = java.time.Duration.ofMillis(1000L);
                """));

        assertEquals(TestValues.DURATION, found.get(0).form().leaf());
        assertEquals(TestValues.DURATION, found.get(1).form().leaf());
        assertEquals("java.time.Duration.ofMillis(3000L)", found.get(0).row().value());
        assertEquals("java.time.Duration.ofMillis(1000L)", found.get(1).row().value());
    }

    @Test
    void aListFieldIsAListShapeAndReadsItemByItem() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static java.util.List<Duration> rests =
                            java.util.List.of(java.time.Duration.ofMillis(3000L),
                                    java.time.Duration.ofMillis(60000L));
                """)).getFirst();

        assertEquals(ValueForm.listOf(TestValues.DURATION), parameter.form());
        assertEquals(TestValues.DURATION, parameter.form().leaf());
        assertEquals(List.of(Duration.ofMillis(3000), Duration.ofMillis(60000)), TestValues.GRAMMAR
                .valueOf(parameter.form(), parameter.row().value()).orElseThrow());
        assertTrue(parameter.editable(), parameter.note());
    }

    // ---- the type is a tree, so a field javac accepts is a field this reads ----------------------------

    /**
     * The checkpoint of {@code 32-generic-values.md}: a {@code Map} field was <em>unknown</em> and
     * read-only, because the deleted {@code ValueChoice} could say a type and one list around it and nothing
     * else.
     */
    @Test
    void aMapFieldIsAMapFormAndReadsBothArguments() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static java.util.Map<String, Duration> waits =
                            java.util.Map.ofEntries(java.util.Map.entry("mine",
                                    java.time.Duration.ofMillis(3000L)));
                """)).getFirst();

        assertEquals(ValueForm.mapOf(TestValues.TEXT, TestValues.DURATION), parameter.form());
        assertTrue(TestValues.GRAMMAR.known(parameter.form()));
        assertTrue(parameter.editable(), parameter.note());
    }

    /** Nesting is unbounded in what may be <em>read</em>; only a picker is capped. */
    @Test
    void aNestedContainerReadsAllTheWayDown() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static java.util.Map<String, java.util.List<Duration>> plans =
                            java.util.Map.ofEntries();
                """)).getFirst();

        assertEquals(ValueForm.mapOf(TestValues.TEXT, ValueForm.listOf(TestValues.DURATION)), parameter.form());
        // A leaf by its simple name; the import is what a declaration adds beside it.
        assertEquals("java.util.Map<String, java.util.List<Duration>>", parameter.form().sourceName());
        assertEquals(List.of("java.time.Duration"), TestValues.GRAMMAR.imports(parameter.form()));
    }

    /**
     * One unknown leaf anywhere makes the whole form unreadable — and the reason names <em>which</em>
     * argument, which is the sentence {@code 32} §<i>A bot's own generic class</i> asks for.
     */
    @Test
    void aContainerOverAnUnknownLeafIsListedAndSaysWhichArgument() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static java.util.Map<String, Channel> routes = java.util.Map.ofEntries();
                """)).getFirst();

        assertFalse(parameter.editable());
        assertFalse(TestValues.GRAMMAR.known(parameter.form()));
        assertTrue(parameter.note().contains("type argument Channel"), parameter.note());
    }

    /** A container nobody registered is not guessed at: it is shown as written and left alone. */
    @Test
    void anUnregisteredContainerIsAnUnknownLeafRatherThanAGuess() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static java.util.Set<String> tags = java.util.Set.of("a");
                """)).getFirst();

        assertFalse(parameter.editable());
        assertEquals("java.util.Set<String>", parameter.form().sourceName());
    }

    /** A hand-written spelling of a container this grammar did not write is kept, not replaced. */
    @Test
    void aMapWrittenWithMapOfIsKeptAsWritten() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static java.util.Map<String, Duration> waits =
                            java.util.Map.of("mine", java.time.Duration.ofMillis(3000L));
                """)).getFirst();

        assertTrue(TestValues.GRAMMAR.known(parameter.form()), "the type is read even when the value is not");
        assertFalse(parameter.editable());
        assertTrue(parameter.note().contains("kept rather than replaced"), parameter.note());
    }

    @Test
    void aFieldWithNoAnnotationIsNotAParameter() {
        assertEquals(List.of(), read(wrap("    public static int notAParameter = 1;\n")));
    }

    @Test
    void oneDeclarationOfTwoFragmentsIsTwoParameters() {
        List<JavaParameter> found = read(wrap("""
                    @Param
                    public static int a = 1, b = 2;
                """));

        assertEquals(List.of("a", "b"), found.stream().map(JavaParameter::name).toList());
        assertEquals("2", found.get(1).row().value());
    }

    @Test
    void parametersInASecondClassCarryThatClassName() {
        List<JavaParameter> found = read("""
                package com.example.bot;
                import com.botmaker.plugin.basics.params.Param;

                public final class Parameters {
                    @Param public static int a = 1;
                }
                class Tuning {
                    @Param public static int b = 2;
                }
                """);

        assertEquals(List.of("Parameters", "Tuning"),
                found.stream().map(JavaParameter::className).toList());
    }

    @Test
    void theAnnotationIsRecognisedHoweverItIsWritten() {
        List<JavaParameter> found = read("""
                package com.example.bot;

                import com.botmaker.plugin.api.params.Param;

                public final class Parameters {
                    @Param public static int imported = 1;
                    @com.botmaker.plugin.basics.params.Param public static int qualified = 2;
                }
                """);

        assertEquals(List.of("imported", "qualified"), found.stream().map(JavaParameter::name).toList());
    }

    @Test
    void aParamNothingImportsIsNotAParameter() {
        // Resolved as javac would: a bare `Param` in com.example.bot is com.example.bot.Param, which is not the
        // contract's. The suffix match this replaced listed it.
        List<JavaParameter> found = read("""
                package com.example.bot;

                public final class Parameters {
                    @Param public static int stray = 1;
                }
                """);

        assertEquals(List.of(), found);
    }

    // ---- what is listed but not editable, and what it says --------------------------------------------

    @Test
    void aHandWrittenInitializerIsKeptAndShownReadOnly() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static Duration rest = java.time.Duration.ofSeconds(3);
                """)).getFirst();

        assertFalse(parameter.editable());
        assertEquals("java.time.Duration.ofSeconds(3)", parameter.initializer());
        assertTrue(parameter.note().contains("kept rather than replaced"), parameter.note());
        // Still listed, still typed: the bot reads it and the window has to show it.
        assertEquals(TestValues.DURATION, parameter.form().leaf());
    }

    @Test
    void anUndeclaredTypeIsListedReadOnlyAndNamesTheTypeNobodyDeclares() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static Channel channel = Channel.GENERAL;
                """)).getFirst();

        assertFalse(parameter.editable());
        assertFalse(TestValues.GRAMMAR.known(parameter.form()));
        assertTrue(parameter.note().contains("declares Channel"), parameter.note());
    }

    @Test
    void theThreeModifierFaultsEachSayWhatIsWrong() {
        List<JavaParameter> found = read(wrap("""
                    @Param
                    static int notPublic = 1;

                    @Param
                    public int notStatic = 2;

                    @Param
                    public static final int isFinal = 3;
                """));

        assertTrue(found.get(0).note().startsWith("not public"), found.get(0).note());
        assertTrue(found.get(1).note().startsWith("not static"), found.get(1).note());
        assertTrue(found.get(2).note().startsWith("final"), found.get(2).note());
        assertTrue(found.stream().noneMatch(JavaParameter::editable));
    }

    @Test
    void aFieldWithNoInitializerSaysThereIsNothingToShow() {
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static int a;
                """)).getFirst();

        assertFalse(parameter.editable());
        assertTrue(parameter.note().startsWith("no initialiser"), parameter.note());
    }

    @Test
    void anArrayIsReadOnlyRatherThanTreatedAsAList() {
        // No container registers an array and no codec emits one, so an array field is shown as written
        // instead of being rewritten into a List the author did not ask for.
        JavaParameter parameter = read(wrap("""
                    @Param
                    public static int[] counts = {1, 2};
                """)).getFirst();

        assertFalse(parameter.editable());
        assertFalse(TestValues.GRAMMAR.known(parameter.form()));
    }

    @Test
    void theAnnotationsOwnConstantsAreReadAsTheStringsTheyAre() {
        // Param.PUBLIC exists so that nobody writes "public"; a reader that could not read it would make
        // the better spelling mean the opposite of what it says.
        List<JavaParameter> found = read(wrap("""
                    @Param(visibility = Param.PUBLIC)
                    public static int a = 1;

                    @Param(visibility = com.botmaker.plugin.basics.params.Param.EDITOR)
                    public static int b = 2;
                """));

        assertEquals(Visibility.PUBLIC, found.get(0).row().visibility());
        assertEquals(Visibility.EDITOR_ONLY, found.get(1).row().visibility());
    }

    @Test
    void aMemberThatIsNotALiteralReadsAsAbsent() {
        // Without bindings a constant's value is unknowable, and a wrong category is worse than none.
        JavaParameter parameter = read(wrap("""
                    @Param(category = SOME_CONSTANT)
                    public static int a = 1;
                """)).getFirst();

        assertEquals("", parameter.row().category());
    }

    @Test
    void sourceThatDoesNotCompileStillYieldsWhatItCan() {
        // A project is edited mid-thought and the window is opened anyway; JDT recovers what it can.
        List<JavaParameter> found = read("""
                package com.example.bot;
                import com.botmaker.plugin.basics.params.Param;

                public final class Parameters {
                    @Param public static int a = 1;
                    public static void broken( {
                }
                """);

        assertEquals(List.of("a"), found.stream().map(JavaParameter::name).toList());
    }
}
