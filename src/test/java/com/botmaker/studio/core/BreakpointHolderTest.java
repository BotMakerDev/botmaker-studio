package com.botmaker.studio.core;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A method's header has no line of its own in the compiled class, so a breakpoint there was accepted, drawn
 * with the old red circle, and never hit. It is refused now, by every path that sets one.
 */
class BreakpointHolderTest {

    private static MethodDeclaration method() {
        var cu = SourceParser.parse("class C { void run() { int x = 1; } }");
        return ((TypeDeclaration) cu.types().getFirst()).getMethods()[0];
    }

    @Test
    void aMethodDeclarationHoldsNoBreakpoint() {
        MethodDeclarationBlock block = new MethodDeclarationBlock("id", method(), null);
        assertFalse(block.canHoldBreakpoint());

        block.toggleBreakpoint();
        assertFalse(block.isBreakpoint(), "toggling does nothing");

        block.setBreakpoint(true);
        assertFalse(block.isBreakpoint(), "nor does restoring one saved before the rule existed");
    }
}
