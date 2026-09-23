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
        return node instanceof Expression parsed ? parsed : null;
    }
}
