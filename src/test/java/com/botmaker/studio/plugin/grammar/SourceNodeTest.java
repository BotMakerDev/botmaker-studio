package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceNodeTest {

    @Test
    void aChildIsShownAsTheTextItWasWrittenAs() {
        SourceNode call = SourceNode.parse("Flow.of( Collect::body ,\n  \"x\" )").orElseThrow();
        MethodInvocation invocation = (MethodInvocation) call.node();
        assertEquals("Collect::body", call.child((Expression) invocation.arguments().get(0)).source());
        assertEquals("\"x\"", call.child((Expression) invocation.arguments().get(1)).source());
    }

    @Test
    void leadingSpaceIsNotPartOfTheSource() {
        assertEquals("a.b(1)", SourceNode.parse("  a.b(1) ").orElseThrow().source());
    }

    @Test
    void textThatIsNotExactlyOneExpressionIsNotParsed() {
        assertTrue(SourceNode.parse("a(").isEmpty());
        assertTrue(SourceNode.parse("a; b").isEmpty());
        assertTrue(SourceNode.parse("  ").isEmpty());
    }
}
