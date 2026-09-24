package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.palette.BlockCatalog;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The switch value's own edits: + case with a free label, − case, and yield inside a case's block. */
class SwitchValueEditsTest {

    private static String source(String body) {
        return """
                package com.mybot;
                public class Subject {
                    enum Mode { FAST, SLOW, OFF }
                    public void run(int month, Mode mode) {
                %s
                    }
                }
                """.formatted(body.indent(8));
    }

    private static SwitchExpression switchOf(EditorFixture f) {
        SwitchExpression[] found = {null};
        f.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override
            public boolean visit(SwitchExpression node) {
                if (found[0] == null) found[0] = node;
                return true;
            }
        });
        return found[0];
    }

    @Test
    void aNewCaseTakesTheNextFreeNumberAndADefaultValue() {
        EditorFixture f = new EditorFixture(source("""
                int days = switch (month) {
                    case 0 -> 31;
                    default -> 30;
                };"""));
        f.editor.addCaseToSwitchExpression(switchOf(f));
        assertNotNull(f.lastCode, f.statusMessages.toString());
        assertTrue(f.lastCode.contains("case 1 -> 0;"), f.lastCode);
        assertTrue(f.lastCode.indexOf("case 1") < f.lastCode.indexOf("default"), "before the default");
    }

    @Test
    void aNewEnumCaseTakesAConstantNoCaseUses() {
        EditorFixture f = new EditorFixture(source("""
                int speed = switch (mode) {
                    case FAST -> 2;
                    case SLOW -> 1;
                    case OFF -> 0;
                };"""));
        f.editor.addCaseToSwitchExpression(switchOf(f));
        assertNull(f.lastCode, "every constant already has a case, so there is no label to add");

        EditorFixture g = new EditorFixture(source("""
                int speed = switch (mode) {
                    case FAST -> 2;
                    default -> 0;
                };"""));
        g.editor.addCaseToSwitchExpression(switchOf(g));
        assertNotNull(g.lastCode, g.statusMessages.toString());
        assertTrue(g.lastCode.contains("case SLOW -> 0;"), g.lastCode);
    }

    @Test
    void aCaseIsRemovedButNotTheDefaultTheSwitchNeeds() {
        String code = source("""
                int days = switch (month) {
                    case 0 -> 31;
                    default -> 30;
                };""");
        EditorFixture f = new EditorFixture(code);
        f.editor.removeCaseFromSwitchExpression(switchOf(f), 0);
        assertNotNull(f.lastCode, f.statusMessages.toString());
        assertFalse(f.lastCode.contains("case 0"), f.lastCode);

        EditorFixture g = new EditorFixture(code);
        g.editor.removeCaseFromSwitchExpression(switchOf(g), 1);
        assertNull(g.lastCode, "an int switch value without default does not compile");
    }

    @Test
    void yieldIsOfferedOnlyInsideACaseBlockAndCompilesThere() {
        EditorFixture f = new EditorFixture(source("""
                int days = switch (month) {
                    case 0 -> {
                        System.out.println(month);
                    }
                    default -> 30;
                };"""));
        Block caseBlock = (Block) switchOf(f).statements().stream()
                .filter(Block.class::isInstance).findFirst().orElseThrow();
        assertTrue(StatementPlacement.allows(BlockCatalog.YIELD, caseBlock));
        assertFalse(StatementPlacement.allows(BlockCatalog.YIELD, f.body("run").getAstNode()));
        assertFalse(StatementPlacement.allows(BlockCatalog.BREAK, caseBlock), "break cannot leave a switch value");

        BodyBlock body = (BodyBlock) f.state.getNodeToBlockMap().get(caseBlock);
        f.editor.addStatement(body, BlockCatalog.YIELD, 1);
        assertNotNull(f.lastCode, f.statusMessages.toString());
        assertTrue(f.lastCode.contains("yield 0;"), f.lastCode);
    }
}
