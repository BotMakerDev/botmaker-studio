package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewrites, asserted on the text that comes out — because the text is what the author reads next.
 *
 * <p>Two properties recur and are worth naming: <b>an edit that cannot be made changes nothing</b> (the
 * source comes back identical, and {@code assertSame} says so), and <b>what was not edited is untouched</b>
 * — the comment, the blank line and the second field are still where the author put them, which is what
 * {@code ASTRewrite} buys over regenerating the file.
 */
class JavaParameterEditsTest {

    private static final Type NUMBER = TestValues.WHOLE_NUMBER;
    private static final Type DURATION = TestValues.DURATION;
    private static final Type TEXT = TestValues.TEXT;

    private static final String SOURCE = """
            package com.example.bot;

            import com.botmaker.plugin.basics.params.Param;
            import java.time.Duration;

            public final class Parameters {

                /** How many times to try before giving up. */
                @Param(category = "Limits")
                public static int maxAttempts = 10;

                @Param
                public static Duration restBetween = java.time.Duration.ofMillis(3000L);
            }
            """;

    /**
     * A value edit spelled the way a window spells one: the grammar writes the Java, and the rewrite takes
     * it already written — since 2026-09-20 the initialiser <em>is</em> the value.
     */
    private static String setValue(String source, String field, Type form, Object value) {
        return JavaParameterEdits.setValue(source, "Parameters", field, initializer(form, value));
    }

    private static String add(String source, String className, String field, Type form,
                              Object value, String category, String description) {
        return JavaParameterEdits.add(source, TestValues.GRAMMAR, className, field, form,
                initializer(form, value), category, description);
    }

    private static String initializer(Type form, Object value) {
        return TestValues.GRAMMAR.initializer(form, value).orElse("");
    }

    // ---- values ---------------------------------------------------------------------------------------

    @Test
    void aValueIsReplacedAndNothingElseMoves() {
        String edited = setValue(SOURCE, "maxAttempts", NUMBER, 25);

        assertTrue(edited.contains("public static int maxAttempts = 25;"), edited);
        assertTrue(edited.contains("/** How many times to try before giving up. */"), edited);
        assertTrue(edited.contains("@Param(category = \"Limits\")"), edited);
        assertTrue(edited.contains("java.time.Duration.ofMillis(3000L)"), edited);
    }

    @Test
    void aStructuralValueIsWrittenAsItsComponentsSpellIt() {
        String edited = setValue(SOURCE, "restBetween", DURATION, Duration.ofMillis(5000));

        assertTrue(edited.contains("restBetween = java.time.Duration.ofMillis(5000L);"), edited);
        // And it reads back, which is the property the window depends on.
        JavaParameter parameter = JavaParameterSource.read(null, edited, TestValues.GRAMMAR).stream()
                .filter(p -> p.name().equals("restBetween")).findFirst().orElseThrow();
        assertEquals("java.time.Duration.ofMillis(5000L)", parameter.row().value());
        assertTrue(parameter.editable());
    }

    @Test
    void aFieldThatIsNotThereChangesNothing() {
        assertSame(SOURCE, setValue(SOURCE, "notDeclared", NUMBER, 1));
        assertSame(SOURCE, JavaParameterEdits.setValue(SOURCE, "Elsewhere", "maxAttempts", "1"));
    }

    @Test
    void aTypeWithNoSourceSpellingChangesNothing() {
        // An undeclared type has no initialiser the grammar can write, so the edit declines rather than
        // writing a field naming a class that does not exist.
        assertSame(SOURCE, setValue(SOURCE, "maxAttempts", new ValueTypes.Unknown("Channel"), "x"));
    }

    @Test
    void aValueWrittenBySimpleNameBringsItsImport() {
        String source = SOURCE.replace("import java.time.Duration;\n", "");
        String edited = JavaParameterEdits.setValue(source, "Parameters", "restBetween",
                "Duration.ofMillis(5L)", List.of("java.time.Duration"));

        assertTrue(edited.contains("import java.time.Duration;"), edited);
        assertTrue(edited.contains("restBetween = Duration.ofMillis(5L);"), edited);
    }

    // ---- renames --------------------------------------------------------------------------------------

    @Test
    void aRenameRepointsTheDeclarationAndTheQualifiedUses() {
        String source = SOURCE + """

                final class Bot {
                    void run() {
                        int n = Parameters.maxAttempts;
                        int other = Elsewhere.maxAttempts;
                        int local = maxAttempts;
                    }
                }
                """;
        String edited = JavaParameterEdits.rename(source, "Parameters", "maxAttempts", "attempts");

        assertTrue(edited.contains("public static int attempts = 10;"), edited);
        assertTrue(edited.contains("int n = Parameters.attempts;"), edited);
        // Somebody else's class, and somebody else's local: neither is this field.
        assertTrue(edited.contains("int other = Elsewhere.maxAttempts;"), edited);
        assertTrue(edited.contains("int local = maxAttempts;"), edited);
    }

    @Test
    void aBareUseInsideTheDeclaringClassIsRepointed() {
        String source = """
                package com.example.bot;
                public final class Parameters {
                    @Param public static int maxAttempts = 10;
                    public static int twice() { return maxAttempts * 2; }
                }
                """;
        String edited = JavaParameterEdits.rename(source, "Parameters", "maxAttempts", "attempts");

        assertTrue(edited.contains("return attempts * 2;"), edited);
    }

    @Test
    void renamingToTheSameNameOrToNothingChangesNothing() {
        assertSame(SOURCE, JavaParameterEdits.rename(SOURCE, "Parameters", "maxAttempts", "maxAttempts"));
        assertSame(SOURCE, JavaParameterEdits.rename(SOURCE, "Parameters", "maxAttempts", "  "));
        assertSame(SOURCE, JavaParameterEdits.rename(SOURCE, "Parameters", "notDeclared", "x"));
    }

    // ---- types ----------------------------------------------------------------------------------------

    @Test
    void retypingChangesTheTypeAndResetsTheValue() {
        String edited = JavaParameterEdits.retype(SOURCE, TestValues.GRAMMAR, "Parameters", "maxAttempts",
                DURATION);

        // By its simple name, since the file imports it — and the import would have been added if not.
        assertTrue(edited.contains("public static Duration maxAttempts"), edited);
        assertTrue(edited.contains("= java.time.Duration.ofMillis(0L);"), edited);
    }

    @Test
    void retypingToATypeTheFileDoesNotImportAddsTheImport() {
        String source = SOURCE.replace("import java.time.Duration;\n", "")
                .replace("public static Duration restBetween", "public static java.time.Duration restBetween");
        String edited = JavaParameterEdits.retype(source, TestValues.GRAMMAR, "Parameters", "maxAttempts",
                DURATION);

        assertTrue(edited.contains("import java.time.Duration;"), edited);
        assertTrue(edited.contains("public static Duration maxAttempts"), edited);
    }

    /** A type with no fresh value is declared with no initialiser at all — legal Java for every type. */
    @Test
    void retypingToATypeWithNoFreshValueLeavesNoInitialiser() {
        String edited = JavaParameterEdits.retype(SOURCE, TestValues.GRAMMAR, "Parameters", "maxAttempts",
                new ValueTypes.BotClass("com.example.bot.Point", List.of()));

        assertTrue(edited.contains("public static com.example.bot.Point maxAttempts;"), edited);
    }

    /**
     * A container's default is an <b>empty</b> one, which is what changed when the type became a tree: the
     * flat pair seeded one item because a list was a shape over a single type's default, and a list a user
     * has not filled in has no items. It is the same rule a plugin's own store has always applied.
     */
    @Test
    void retypingToAListWritesTheListTypeAndAnEmptyList() {
        String edited = JavaParameterEdits.retype(SOURCE, TestValues.GRAMMAR, "Parameters", "maxAttempts",
                ValueTypes.listOf(TestValues.WHOLE_NUMBER));

        // Boxed: List<int> does not compile.
        assertTrue(edited.contains("java.util.List<Integer> maxAttempts"), edited);
        assertTrue(edited.contains("java.util.List.of()"), edited);
    }

    /** A map is a type a bot may declare, so it is a type this window may write. */
    @Test
    void retypingToAMapWritesBothArgumentsAndAnEmptyMap() {
        String edited = JavaParameterEdits.retype(SOURCE, TestValues.GRAMMAR, "Parameters", "maxAttempts",
                ValueTypes.mapOf(TestValues.TEXT, TestValues.DURATION));

        assertTrue(edited.contains("java.util.Map<String, Duration> maxAttempts"), edited);
        assertTrue(edited.contains("java.util.Map.ofEntries()"), edited);
    }

    // ---- annotation members ---------------------------------------------------------------------------

    @Test
    void aMemberIsAddedToAnAnnotationThatHasOthers() {
        String edited = JavaParameterEdits.setMembers(SOURCE, "Parameters", "maxAttempts",
                Map.of("description", "How many times to try"));

        assertTrue(edited.contains("category = \"Limits\""), edited);
        assertTrue(edited.contains("description = \"How many times to try\""), edited);
    }

    @Test
    void aBareParamGrowsMembersWhenOneIsSet() {
        String edited = JavaParameterEdits.setMembers(SOURCE, "Parameters", "restBetween",
                Map.of("category", "Timing"));

        assertTrue(edited.contains("@Param(category = \"Timing\")"), edited);
    }

    @Test
    void aBlankValueRemovesTheMemberRatherThanWritingAnEmptyOne() {
        Map<String, String> edits = new LinkedHashMap<>();
        edits.put("category", "");
        String edited = JavaParameterEdits.setMembers(SOURCE, "Parameters", "maxAttempts", edits);

        assertFalse(edited.contains("category"), edited);
        assertTrue(edited.contains("public static int maxAttempts = 10;"), edited);
    }

    @Test
    void optionsAreWrittenAsAnArrayAndClearedWhenEmpty() {
        String withOptions = JavaParameterEdits.setOptions(SOURCE, "Parameters", "maxAttempts",
                List.of("one", "two"));
        assertTrue(withOptions.contains("options = { \"one\", \"two\" }")
                || withOptions.contains("options = {\"one\", \"two\"}"), withOptions);

        String cleared = JavaParameterEdits.setOptions(withOptions, "Parameters", "maxAttempts", List.of());
        assertFalse(cleared.contains("options"), cleared);
    }

    // ---- adding and removing --------------------------------------------------------------------------

    @Test
    void anAddedParameterIsAPublicStaticAnnotatedField() {
        String edited = add(SOURCE, "Parameters", "label", TEXT, "hello", "Naming", "What to call it");

        assertTrue(edited.contains("@Param(category = \"Naming\", description = \"What to call it\")"),
                edited);
        assertTrue(edited.contains("public static String label = \"hello\";"), edited);
        // Appended: the author's own order is theirs.
        assertTrue(edited.indexOf("restBetween") < edited.indexOf("label"), edited);
    }

    @Test
    void anAddedParameterIsIndentedWithSpacesLikeTheRestOfTheFile() {
        String edited = add(SOURCE, "Parameters", "label", TEXT, "hello", "", "");

        assertFalse(edited.contains("\t"), edited);
        assertTrue(edited.contains("\n    public static String label = \"hello\";"), edited);
    }

    @Test
    void aPublicVisibilityIsWrittenAsTheAnnotationsConstant() {
        String edited = JavaParameterEdits.setMembers(SOURCE, "Parameters", "maxAttempts",
                Map.of("visibility", "public"));

        assertTrue(edited.contains("visibility = Param.PUBLIC"), edited);
        assertFalse(edited.contains("\"public\""), edited);
        JavaParameter parameter = JavaParameterSource.read(null, edited, TestValues.GRAMMAR).stream()
                .filter(p -> p.name().equals("maxAttempts")).findFirst().orElseThrow();
        assertEquals(com.botmaker.plugin.api.value.Visibility.PUBLIC, parameter.row().visibility());
    }

    @Test
    void anAddedParameterWithNothingToSayGetsABareAnnotation() {
        String edited = add(SOURCE, "Parameters", "label", TEXT, "hello", "", "");

        assertTrue(edited.contains("@Param" + System.lineSeparator())
                || edited.contains("@Param\n") || edited.contains("@Param "), edited);
        assertFalse(edited.contains("@Param()"), edited);
    }

    @Test
    void addingANameThatIsTakenOrAClassThatIsNotThereChangesNothing() {
        assertSame(SOURCE, add(SOURCE, "Parameters", "maxAttempts", NUMBER, 1, "", ""));
        assertSame(SOURCE, add(SOURCE, "Missing", "a", NUMBER, 1, "", ""));
        assertSame(SOURCE, add(SOURCE, "Parameters", "  ", NUMBER, 1, "", ""));
    }

    @Test
    void removingTakesTheWholeDeclarationWithItsAnnotation() {
        String edited = JavaParameterEdits.remove(SOURCE, "Parameters", "maxAttempts");

        assertFalse(edited.contains("maxAttempts"), edited);
        assertFalse(edited.contains("category = \"Limits\""), edited);
        assertTrue(edited.contains("restBetween"), edited);
    }

    @Test
    void removingOneOfTwoFragmentsLeavesTheOther() {
        String source = """
                package com.example.bot;
                public final class Parameters {
                    @Param public static int a = 1, b = 2;
                }
                """;
        String edited = JavaParameterEdits.remove(source, "Parameters", "a");

        assertFalse(edited.contains("a = 1"), edited);
        assertTrue(edited.contains("b = 2"), edited);
    }

    @Test
    void removingSomethingThatIsNotThereChangesNothing() {
        assertSame(SOURCE, JavaParameterEdits.remove(SOURCE, "Parameters", "notDeclared"));
        assertSame(SOURCE, JavaParameterEdits.remove(SOURCE, "Elsewhere", "maxAttempts"));
    }

    // ---- the round trip -------------------------------------------------------------------------------

    @Test
    void whatIsWrittenIsWhatIsReadBack() {
        String edited = add(SOURCE, "Parameters", "label", TEXT, "a \"quoted\" value", "Naming", "");
        JavaParameter parameter = JavaParameterSource.read(null, edited, TestValues.GRAMMAR).stream()
                .filter(p -> p.name().equals("label")).findFirst().orElseThrow();

        assertEquals("\"a \\\"quoted\\\" value\"", parameter.row().value());
        assertEquals("a \"quoted\" value", TestValues.GRAMMAR.valueOf(TEXT, parameter.row().value()).orElseThrow());
        assertEquals("Naming", parameter.row().category());
        assertTrue(parameter.editable(), parameter.note());
    }
}
