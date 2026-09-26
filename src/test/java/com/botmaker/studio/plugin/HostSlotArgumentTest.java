package com.botmaker.studio.plugin;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A slot reads its neighbours as values: what a Precision editor needs of the Color beside it. */
class HostSlotArgumentTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                static int limit = 9;
                public static void run() {
                    int a = Math.max(3, 4);
                    int b = Math.max(limit, 4);
                    String c = String.format("%d %d", 1, 2);
                }
            }
            """;

    private static HostSlotContext slot(EditorFixture fixture, String method, int occurrence, int argIndex) {
        MethodInvocation[] found = new MethodInvocation[1];
        int[] seen = {0};
        fixture.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (node.getName().getIdentifier().equals(method) && seen[0]++ == occurrence) found[0] = node;
                return true;
            }
        });
        MethodInvocation call = found[0];
        Expression argument = (Expression) call.arguments().get(argIndex);
        return new HostSlotContext(fixture.context(), ValueSlot.at(() -> argument),
                ResolvedType.of(call.resolveMethodBinding().getParameterTypes()[argIndex]),
                call.resolveMethodBinding(), argIndex, null);
    }

    @Test
    void aLiteralNeighbourIsReadAsItsValue() {
        HostSlotContext second = slot(new EditorFixture(SOURCE), "max", 0, 1);

        assertEquals(3, second.argumentValue(0, Integer.class).orElseThrow());
    }

    @Test
    void itsOwnIndexIsItsOwnValue() {
        HostSlotContext second = slot(new EditorFixture(SOURCE), "max", 0, 1);

        assertEquals(4, second.argumentValue(1, Integer.class).orElseThrow());
    }

    @Test
    void aVariableAnOutOfRangeIndexAndAVarargsTailAreUnreadable() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        assertTrue(slot(fixture, "max", 1, 1).argumentValue(0, Integer.class).isEmpty(), "a variable");
        assertTrue(slot(fixture, "max", 0, 1).argumentValue(2, Integer.class).isEmpty(), "past the call");
        assertTrue(slot(fixture, "max", 0, 1).argumentValue(-1, Integer.class).isEmpty(), "negative");
        assertTrue(slot(fixture, "format", 0, 0).argumentValue(1, Integer.class).isEmpty(), "varargs tail");
    }

    @Test
    void theWrongTypeIsEmpty() {
        assertTrue(slot(new EditorFixture(SOURCE), "max", 0, 1).argumentValue(0, String.class).isEmpty());
    }
}
