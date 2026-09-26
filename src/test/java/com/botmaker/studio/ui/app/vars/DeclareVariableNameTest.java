package com.botmaker.studio.ui.app.vars;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The name Declare Variable suggests, and the ones it refuses. */
class DeclareVariableNameTest {

    @Test
    void theSuggestionIsFree() {
        assertEquals("number", DeclareVariableDialog.free("number", Set.of()));
        assertEquals("number3", DeclareVariableDialog.free("number", Set.of("number", "number2")));
    }

    @Test
    void aKeywordATakenNameAndBlankAreRefused() {
        assertNotNull(DeclareVariableDialog.problem("int", Set.of()));
        assertNotNull(DeclareVariableDialog.problem("count", Set.of("count")));
        assertNotNull(DeclareVariableDialog.problem("  ", Set.of()));
        assertNull(DeclareVariableDialog.problem("count", Set.of()));
    }
}
