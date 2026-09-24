package com.botmaker.studio.core.render;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A value's outline is read off its syntax, with no bindings — the canvas is often drawn from a tree parsed
 * without a classpath, and a condition must still look like a condition there.
 */
class BlockShapeTest {

    private static ASTNode expression(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setCompilerOptions(Map.of(
                JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion(),
                JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion(),
                JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.latestSupportedJavaVersion()));
        parser.setSource(source.toCharArray());
        return parser.createAST(null);
    }

    @Test
    void aYesNoValueIsBoolean() {
        for (String source : new String[]{
                "a < b", "a >= b", "a == b", "a != b", "a && b", "a || b", "!done", "true", "false",
                "o instanceof String", "o instanceof String s", "(a < b)", "((a && b))"}) {
            assertEquals(BlockShape.BOOLEAN, BlockShape.ofValue(expression(source)), source);
        }
    }

    @Test
    void anyOtherValueIsAReporter() {
        for (String source : new String[]{
                "a + b", "count", "\"text\"", "42", "-x", "list.get(0)", "new Point(1, 2)", "(a + b)"}) {
            assertEquals(BlockShape.REPORTER, BlockShape.ofValue(expression(source)), source);
        }
    }

    @Test
    void everyShapeButNoneHasItsOwnClass() {
        assertNull(BlockShape.NONE.styleClass(), "NONE is the absence of an outline, and carries no class");
        long distinct = Arrays.stream(BlockShape.values()).map(BlockShape::styleClass).filter(c -> c != null)
                .distinct().count();
        assertEquals(BlockShape.values().length - 1, distinct, "two shapes must not share a class");
        for (BlockShape shape : BlockShape.values()) {
            assertNotNull(shape.id());
            if (shape != BlockShape.NONE) assertEquals(0, shape.styleClass().indexOf("shape-"), shape.styleClass());
        }
    }
}
