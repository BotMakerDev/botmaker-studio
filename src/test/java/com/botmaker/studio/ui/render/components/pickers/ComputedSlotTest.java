package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which slots no value editor draws: a computation is drawn as its own block, a value by its type's editor. */
class ComputedSlotTest {

    /** The argument of {@code use(<argument>);}, as the slot a picker sees. */
    private static ValueSlot slot(String argument) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(("class T { void run(int attempt) { use(" + argument + "); } }").toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        MethodDeclaration run = ((TypeDeclaration) unit.types().getFirst()).getMethods()[0];
        ExpressionStatement statement = (ExpressionStatement) run.getBody().statements().getFirst();
        Expression expression = (Expression) ((MethodInvocation) statement.getExpression()).arguments().getFirst();
        return () -> expression;
    }

    @Test
    void aForLoopsConditionAndStepAreComputations() {
        assertTrue(PickerRegistry.computed(slot("attempt < 5")));
        assertTrue(PickerRegistry.computed(slot("attempt++")));
        assertTrue(PickerRegistry.computed(slot("!found")));
        assertTrue(PickerRegistry.computed(slot("attempt = 2")));
        assertTrue(PickerRegistry.computed(slot("found ? 1 : 2")));
    }

    @Test
    void aValueIsLeftToTheEditors() {
        assertFalse(PickerRegistry.computed(slot("5")));
        assertFalse(PickerRegistry.computed(slot("-5")));
        assertFalse(PickerRegistry.computed(slot("true")));
        assertFalse(PickerRegistry.computed(slot("Pictures.HOME")));
        assertFalse(PickerRegistry.computed(slot("Duration.ofSeconds(3)")));
        assertFalse(PickerRegistry.computed(ValueSlot.empty()));
    }
}
