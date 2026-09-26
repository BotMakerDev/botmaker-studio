package com.botmaker.studio.ui.render.menu;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "New int variable…" once proposed {@code int} as the name, which JDT refuses as an identifier and the menu
 * died with {@code Invalid identifier : >int<}.
 */
class FreshVariableNameTest {

    @Test
    void aClassTypeIsItsLowerCasedName() {
        assertEquals("direction", ExpressionMenu.freshVariableName("Direction", Set.of()));
    }

    @Test
    void aPrimitiveIsNeverTheKeyword() {
        assertEquals("number", ExpressionMenu.freshVariableName("int", Set.of()));
        assertEquals("flag", ExpressionMenu.freshVariableName("boolean", Set.of()));
        assertEquals("decimal", ExpressionMenu.freshVariableName("double", Set.of()));
        assertEquals("variable", ExpressionMenu.freshVariableName("char", Set.of()));
    }

    @Test
    void aTakenNameIsSuffixed() {
        assertEquals("number2", ExpressionMenu.freshVariableName("int", Set.of("number")));
        assertEquals("point3", ExpressionMenu.freshVariableName("Point", Set.of("point", "point2")));
    }
}
