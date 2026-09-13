package com.botmaker.studio.project;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Breakpoints are keyed by the file they were set in, and this is the test that says why.
 *
 * <p>A block id is a structural path inside one compilation unit, so
 * {@code types[0]/bodyDeclarations[0]/body/statements[0]} names the first statement of the first method of
 * <em>every</em> file in the project. The set was flat until 2026-09-13; with path ids that would have put a
 * breakpoint from one file onto whichever file was opened next, and with the positional ids before them the
 * same collision was possible and merely rarer.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class BreakpointStoreTest {

    private static final Path MINING = Paths.get("/tmp/projects/MyBot/src/main/java/com/mybot/Mining.java");
    private static final Path FISHING = Paths.get("/tmp/projects/MyBot/src/main/java/com/mybot/Fishing.java");

    /** The id every file's first statement answers to — which is the whole point. */
    private static final String FIRST_STATEMENT = "types[0]/bodyDeclarations[0]/body/statements[0]";

    @Test
    void one_id_in_two_files_is_two_different_breakpoints() {
        ProjectState state = new ProjectState();
        state.addBreakpoint(MINING, FIRST_STATEMENT);

        assertTrue(state.hasBreakpoint(MINING, FIRST_STATEMENT));
        assertFalse(state.hasBreakpoint(FISHING, FIRST_STATEMENT),
                "a breakpoint in Mining must not appear in Fishing");
    }

    @Test
    void removing_one_files_breakpoint_leaves_the_others_alone() {
        ProjectState state = new ProjectState();
        state.addBreakpoint(MINING, FIRST_STATEMENT);
        state.addBreakpoint(FISHING, FIRST_STATEMENT);

        state.removeBreakpoint(MINING, FIRST_STATEMENT);

        assertFalse(state.hasBreakpoint(MINING, FIRST_STATEMENT));
        assertTrue(state.hasBreakpoint(FISHING, FIRST_STATEMENT));
    }

    @Test
    void a_breakpoint_set_with_no_file_open_belongs_to_no_file() {
        // getActiveFile() answers null with nothing open, and that is a legal key rather than a crash.
        ProjectState state = new ProjectState();
        state.addBreakpoint(null, FIRST_STATEMENT);

        assertTrue(state.hasBreakpoint(null, FIRST_STATEMENT));
        assertFalse(state.hasBreakpoint(MINING, FIRST_STATEMENT));
    }

    @Test
    void removing_something_that_was_never_set_is_a_no_op() {
        ProjectState state = new ProjectState();

        state.removeBreakpoint(MINING, FIRST_STATEMENT);
        state.addBreakpoint(MINING, FIRST_STATEMENT);
        state.removeBreakpoint(MINING, "types[0]/bodyDeclarations[9]/body/statements[0]");

        assertTrue(state.hasBreakpoint(MINING, FIRST_STATEMENT), "the real one survived");
    }
}
