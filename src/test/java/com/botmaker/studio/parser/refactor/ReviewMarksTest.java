package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review marker, on its own: writing the contract's {@code @Refactor}, merging into one that is there,
 * reading it back, marking it reviewed and removing it.
 *
 * <p>Every test that writes asserts the result <b>parses</b>. An annotation is one of the few edits that can
 * be recorded happily by {@code ASTRewrite} and still land in a position Java does not allow — before the
 * javadoc, after {@code public} — so "it compiles" is the assertion that matters, not "the text is there".
 *
 * <p>The units here have no bindings, so the annotation is recognised the way javac would without a
 * classpath: through the file's import of {@code com.botmaker.plugin.api.meta.Refactor}. A {@code Refactor}
 * nothing imports is someone else's.
 */
class ReviewMarksTest {

    private static final String IMPORT = "import com.botmaker.plugin.api.meta.Refactor;\n";

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /** Applies {@code edit} to the one method named {@code method} in {@code source}, and returns the result. */
    private static String edit(String source, String method, Editor edit) {
        CompilationUnit unit = SourceParser.parse(source);
        assertNotNull(unit);
        EditContext ctx = EditContext.of(unit, null, null);
        edit.apply(ctx, methodNamed(unit, method));
        String rewritten = ctx.applyTo(source);
        assertNotNull(rewritten, "the rewrite did not apply");
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(rewritten)),
                "a marked file must still compile:\n" + rewritten);
        return rewritten;
    }

    private interface Editor {
        void apply(EditContext ctx, MethodDeclaration method);
    }

    private static MethodDeclaration methodNamed(CompilationUnit unit, String name) {
        AtomicReference<MethodDeclaration> found = new AtomicReference<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(name)) found.set(node);
                return true;
            }
        });
        assertNotNull(found.get(), "no method named " + name);
        return found.get();
    }

    /** What {@code source} says {@code method}'s mark is, read back through a fresh parse. */
    private static ReviewMarks.Mark markIn(String source, String method) {
        return ReviewMarks.markOn(methodNamed(SourceParser.parse(source), method));
    }

    private static final String PLAIN = """
            package com.mybot;
            class Bot {
                void run() {
                    int x = 1;
                }
            }
            """;

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    @Test
    void aFunctionWithNoMarkGainsOneAndTheImport() {
        String source = edit(PLAIN, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("look at this")));

        assertTrue(source.contains("@Refactor(\"look at this\")"), source);
        assertTrue(source.contains(IMPORT.strip()), source);
        assertEquals(new ReviewMarks.Mark(List.of("look at this"), false), markIn(source, "run"));
    }

    @Test
    void severalEntriesAreWrittenAsAnArrayAndReadBackInOrder() {
        String source = edit(PLAIN, "run",
                (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("first", "second")));

        assertTrue(source.contains("{"), source);
        assertEquals(List.of("first", "second"), markIn(source, "run").entries());
    }

    @Test
    void aSecondRefactorMergesIntoTheMarkAlreadyThereRatherThanAddingAnother() {
        String once = edit(PLAIN, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("first")));
        String twice = edit(once, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("second")));

        assertEquals(1, twice.split("@Refactor", -1).length - 1, "Java allows a method one of these:\n" + twice);
        assertEquals(List.of("first", "second"), markIn(twice, "run").entries());
    }

    @Test
    void theSameEntryTwiceIsStillOneEntry() {
        String once = edit(PLAIN, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("same")));
        String twice = edit(once, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("same")));

        assertEquals(List.of("same"), markIn(twice, "run").entries());
    }

    @Test
    void quotesAndBackslashesInAnEntrySurviveTheRoundTrip() {
        String entry = "Vision.find(\"a\\b\") is gone";
        String source = edit(PLAIN, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of(entry)));

        assertEquals(List.of(entry), markIn(source, "run").entries());
    }

    @Test
    void aMarkGoesAheadOfTheModifiersAndBelowTheJavadoc() {
        String source = edit("""
                package com.mybot;
                class Bot {
                    /** Does the thing. */
                    public static void run() {
                        int x = 1;
                    }
                }
                """, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("entry")));

        int javadoc = source.indexOf("Does the thing");
        int mark = source.indexOf("@Refactor");
        int modifiers = source.indexOf("public static");
        assertTrue(javadoc < mark && mark < modifiers, "wrong order:\n" + source);
    }

    @Test
    void anEmptyListOfEntriesMarksNothingAtAll() {
        String source = edit(PLAIN, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of()));

        assertFalse(source.contains("Refactor"), source);
    }

    @Test
    void aNewGuessReopensAReviewedMark() {
        String reviewed = """
                package com.mybot;
                %s
                class Bot {
                    @Refactor(value = "old", done = true)
                    void run() {}
                }
                """.formatted(IMPORT);
        String source = edit(reviewed, "run", (ctx, method) -> ReviewMarks.mark(ctx, method, List.of("new")));

        assertEquals(new ReviewMarks.Mark(List.of("old", "new"), false), markIn(source, "run"));
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    @Test
    void aHandWrittenNormalAnnotationReadsBackToo() {
        String source = """
                package com.mybot;
                %s
                class Bot {
                    @Refactor(value = {"one", "two"}, done = false)
                    void run() {}
                }
                """.formatted(IMPORT);
        assertEquals(new ReviewMarks.Mark(List.of("one", "two"), false), markIn(source, "run"));
    }

    @Test
    void aRefactorNothingImportsIsNotTheContracts() {
        String source = """
                package com.mybot;
                class Bot {
                    @Refactor("someone else's")
                    void run() {}
                }
                """;
        assertNull(markIn(source, "run"));
    }

    @Test
    void anUnmarkedFunctionHasNoMarkAndNoOpenEntries() {
        assertNull(markIn(PLAIN, "run"));
        assertEquals(List.of(), ReviewMarks.openEntriesOf(methodNamed(SourceParser.parse(PLAIN), "run")));
    }

    @Test
    void everyMarkedFunctionInAFileIsFoundInSourceOrderReviewedOrNot() {
        CompilationUnit unit = SourceParser.parse("""
                package com.mybot;
                %s
                class Bot {
                    @Refactor("a")
                    void first() {}
                    void second() {}
                    @Refactor(value = "b", done = true)
                    void third() {}
                }
                """.formatted(IMPORT));

        assertEquals(List.of("first", "third"),
                ReviewMarks.markedIn(unit).stream().map(m -> m.getName().getIdentifier()).toList());
        assertEquals(List.of(), ReviewMarks.openEntriesOf(methodNamed(unit, "third")),
                "a reviewed mark has nothing open");
    }

    // -------------------------------------------------------------------------
    // Reviewing and removing
    // -------------------------------------------------------------------------

    @Test
    void markingReviewedKeepsTheRecordAndSaysDone() {
        String marked = """
                package com.mybot;
                %s
                class Bot {
                    @Refactor({"first", "second"})
                    void run() {}
                }
                """.formatted(IMPORT);
        String source = edit(marked, "run", ReviewMarks::markReviewed);

        assertEquals(new ReviewMarks.Mark(List.of("first", "second"), true), markIn(source, "run"));
        assertTrue(source.contains("done = true"), source);
        assertTrue(source.contains(IMPORT.strip()), "the record still names the annotation:\n" + source);
    }

    @Test
    void markingReviewedTwiceChangesNothingAndSaysSo() {
        CompilationUnit unit = SourceParser.parse("""
                package com.mybot;
                %s
                class Bot {
                    @Refactor(value = "x", done = true)
                    void run() {}
                }
                """.formatted(IMPORT));
        EditContext ctx = EditContext.of(unit, null, null);

        assertFalse(ReviewMarks.markReviewed(ctx, methodNamed(unit, "run")));
    }

    @Test
    void removingTheLastMarkTakesTheImportWithIt() {
        String marked = """
                package com.mybot.activities;

                %s
                class Mining {
                    @Refactor("only")
                    void run() {}
                }
                """.formatted(IMPORT);
        String source = edit(marked, "run", ReviewMarks::remove);

        assertFalse(source.contains("Refactor"), source);
    }

    @Test
    void theImportStaysWhileAnotherFunctionInTheFileIsStillMarked() {
        String marked = """
                package com.mybot.activities;

                %s
                class Mining {
                    @Refactor("mine")
                    void first() {}
                    @Refactor("keep")
                    void second() {}
                }
                """.formatted(IMPORT);
        String source = edit(marked, "first", ReviewMarks::remove);

        assertTrue(source.contains(IMPORT.strip()), source);
        assertEquals(List.of("keep"), markIn(source, "second").entries());
    }

    @Test
    void removingSomethingThatIsNotThereChangesNothingAndSaysSo() {
        CompilationUnit unit = SourceParser.parse(PLAIN);
        EditContext ctx = EditContext.of(unit, null, null);

        assertFalse(ReviewMarks.remove(ctx, methodNamed(unit, "run")));
        assertEquals(PLAIN, ctx.applyTo(PLAIN));
    }
}
