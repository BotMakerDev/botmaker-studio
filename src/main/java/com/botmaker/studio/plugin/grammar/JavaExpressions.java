package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Expression;

import java.util.Map;

/**
 * One Java expression parsed on its own, with no bindings — the one parser the grammar reads with, and the
 * configuration {@code project/params} shares. {@link SourceNode#parse} is the door that also checks the
 * text was exactly one expression.
 */
public final class JavaExpressions {

    private JavaExpressions() {}

    /**
     * The property set on the root of a tree this class parsed: an expression with no file around it. JDT
     * wraps one in a synthetic unit, so the tree alone cannot say its imports are missing rather than empty.
     */
    static final String DETACHED = "botmaker.detached";

    /** Whether {@code node} came from {@link #parse} — an expression with no file, so no imports to read. */
    public static boolean detached(ASTNode node) {
        return node != null && Boolean.TRUE.equals(node.getRoot().getProperty(DETACHED));
    }

    /** {@code source} as an expression, or {@code null} when the text is not exactly one. */
    public static Expression parse(String source) {
        if (source == null || source.isBlank()) return null;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);
        ASTNode node = parser.createAST(null);
        if (!(node instanceof Expression parsed)) return null;
        parsed.getRoot().setProperty(DETACHED, Boolean.TRUE);
        return parsed;
    }
}
