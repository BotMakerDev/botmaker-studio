package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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

    private static boolean computed(String argument) {
        return PickerRegistry.computed(slot(argument), ResolvedType.UNKNOWN);
    }

    @Test
    void aForLoopsConditionAndStepAreComputations() {
        assertTrue(computed("attempt < 5"));
        assertTrue(computed("attempt++"));
        assertTrue(computed("!found"));
        assertTrue(computed("attempt = 2"));
        assertTrue(computed("found ? 1 : 2"));
    }

    /** {@code if (ImageWaiter.waitFor(…))} was drawn as an "Off" toggle (2026-10-30). */
    @Test
    void aCallWhereALiteralGoesIsAComputation() {
        assertTrue(PickerRegistry.computed(slot("ImageWaiter.waitFor(Pictures.BATTLE)"), ResolvedType.BOOLEAN));
        assertTrue(PickerRegistry.computed(slot("Navigate.press(Screens.HOME).done()"), ResolvedType.BOOLEAN));
        assertTrue(PickerRegistry.computed(slot("(ImageWaiter.waitFor(Pictures.BATTLE))"), ResolvedType.BOOLEAN));
        assertTrue(PickerRegistry.computed(slot("count()"), ResolvedType.INT));
        assertTrue(PickerRegistry.computed(slot("name()"), ResolvedType.named("java.lang.String")));
    }

    @Test
    void aValueIsLeftToTheEditors() {
        assertFalse(computed("5"));
        assertFalse(computed("-5"));
        assertFalse(computed("true"));
        assertFalse(computed("Pictures.HOME"));
        assertFalse(PickerRegistry.computed(slot("Duration.ofSeconds(3)"), ResolvedType.of(Duration.class)));
        assertFalse(PickerRegistry.computed(ValueSlot.empty(), ResolvedType.BOOLEAN));
    }
}
