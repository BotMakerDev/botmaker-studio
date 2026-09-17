package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.value.ValueChoice;
import org.junit.jupiter.api.Test;

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

    private static final ValueChoice NUMBER = ValueChoice.of(TestValues.WHOLE_NUMBER);
    private static final ValueChoice DURATION = ValueChoice.of(TestValues.DURATION);
    private static final ValueChoice TEXT = ValueChoice.of(TestValues.TEXT);

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

    private static String setValue(String source, String field, ValueChoice choice, String... value) {
        return JavaParameterEdits.setValue(source, TestValues.CATALOG, "Parameters", field, choice,
                List.of(value));
    }

    // ---- values ---------------------------------------------------------------------------------------

    @Test
    void aValueIsReplacedAndNothingElseMoves() {
        String edited = setValue(SOURCE, "maxAttempts", NUMBER, "25");

        assertTrue(edited.contains("public static int maxAttempts = 25;"), edited);
        assertTrue(edited.contains("/** How many times to try before giving up. */"), edited);
        assertTrue(edited.contains("@Param(category = \"Limits\")"), edited);
        assertTrue(edited.contains("java.time.Duration.ofMillis(3000L)"), edited);
    }

    @Test
    void aStructuralValueIsWrittenAsTheCodecSpellsIt() {
        String edited = setValue(SOURCE, "restBetween", DURATION, "5000");

        assertTrue(edited.contains("restBetween = java.time.Duration.ofMillis(5000L);"), edited);
        // And it reads back, which is the property the window depends on.
        JavaParameter parameter = JavaParameterSource.read(null, edited, TestValues.CATALOG).stream()
                .filter(p -> p.name().equals("restBetween")).findFirst().orElseThrow();
        assertEquals(List.of("5000"), parameter.row().value());
        assertTrue(parameter.editable());
    }

    @Test
    void aFieldThatIsNotThereChangesNothing() {
        assertSame(SOURCE, setValue(SOURCE, "notDeclared", NUMBER, "1"));
        assertSame(SOURCE, JavaParameterEdits.setValue(SOURCE, TestValues.CATALOG, "Elsewhere",
                "maxAttempts", NUMBER, List.of("1")));
    }

    @Test
    void aTypeWithNoSourceSpellingChangesNothing() {
        // An unknown type has no initialiser the catalog can write, so the edit declines rather than
        // writing a field naming a class that does not exist.
        assertSame(SOURCE, setValue(SOURCE, "maxAttempts",
                ValueChoice.of(com.botmaker.plugin.api.value.ValueType.unknown("CHANNEL")), "x"));
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
        String edited = JavaParameterEdits.retype(SOURCE, TestValues.CATALOG, "Parameters", "maxAttempts",
                DURATION);

        assertTrue(edited.contains("public static java.time.Duration maxAttempts"), edited);
        assertTrue(edited.contains("= java.time.Duration.ofMillis(0L);"), edited);
    }

    @Test
    void retypingToAListWritesTheListType() {
        String edited = JavaParameterEdits.retype(SOURCE, TestValues.CATALOG, "Parameters", "maxAttempts",
                ValueChoice.listOf(TestValues.WHOLE_NUMBER));

        // Boxed: List<int> does not compile.
        assertTrue(edited.contains("java.util.List<Integer> maxAttempts"), edited);
        assertTrue(edited.contains("java.util.List.of(0)"), edited);
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
        String edited = JavaParameterEdits.add(SOURCE, TestValues.CATALOG, "Parameters", "label", TEXT,
                List.of("hello"), "Naming", "What to call it");

        assertTrue(edited.contains("@Param(category = \"Naming\", description = \"What to call it\")"),
                edited);
        assertTrue(edited.contains("public static String label = \"hello\";"), edited);
        // Appended: the author's own order is theirs.
        assertTrue(edited.indexOf("restBetween") < edited.indexOf("label"), edited);
    }

    @Test
    void anAddedParameterWithNothingToSayGetsABareAnnotation() {
        String edited = JavaParameterEdits.add(SOURCE, TestValues.CATALOG, "Parameters", "label", TEXT,
                List.of("hello"), "", "");

        assertTrue(edited.contains("@Param" + System.lineSeparator())
                || edited.contains("@Param\n") || edited.contains("@Param "), edited);
        assertFalse(edited.contains("@Param()"), edited);
    }

    @Test
    void addingANameThatIsTakenOrAClassThatIsNotThereChangesNothing() {
        assertSame(SOURCE, JavaParameterEdits.add(SOURCE, TestValues.CATALOG, "Parameters", "maxAttempts",
                NUMBER, List.of("1"), "", ""));
        assertSame(SOURCE, JavaParameterEdits.add(SOURCE, TestValues.CATALOG, "Missing", "a",
                NUMBER, List.of("1"), "", ""));
        assertSame(SOURCE, JavaParameterEdits.add(SOURCE, TestValues.CATALOG, "Parameters", "  ",
                NUMBER, List.of("1"), "", ""));
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
        String edited = JavaParameterEdits.add(SOURCE, TestValues.CATALOG, "Parameters", "label", TEXT,
                List.of("a \"quoted\" value"), "Naming", "");
        JavaParameter parameter = JavaParameterSource.read(null, edited, TestValues.CATALOG).stream()
                .filter(p -> p.name().equals("label")).findFirst().orElseThrow();

        assertEquals(List.of("a \"quoted\" value"), parameter.row().value());
        assertEquals("Naming", parameter.row().category());
        assertTrue(parameter.editable(), parameter.note());
    }
}
