package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Type;

import java.util.Map;

/**
 * Text the user typed, read as Java — the one parser setup for every edit that takes a snippet rather than a
 * tree: a statement or an expression typed into a source block, a type typed into a cast or a check.
 *
 * <p>Two things every such parse needs and a bare {@code ASTParser} does not do. It reads at the newest Java
 * the parser knows: left at its defaults it reads Java 1.3, where {@code assert} is a name, and a switch
 * expression, a text block, a lambda and {@code var} are errors. And it answers "is this well-formed?" by
 * the parser's own marks ({@code MALFORMED}, {@code RECOVERED}) rather than by a result being non-null, since
 * a parser handed a typo still hands back a tree — one it made up.
 */
public final class JavaSnippets {

    private JavaSnippets() {}

    /** A parser of {@code kind} ({@link ASTParser#K_EXPRESSION}, …) reading the newest Java. */
    public static ASTParser parser(int kind, String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(kind);
        parser.setSource(source.toCharArray());
        return parser;
    }

    /** {@code text} as one expression, or {@code null} when it is blank or not a well-formed one. */
    public static Expression expression(String text) {
        if (text == null || text.isBlank()) return null;
        ASTNode parsed = parser(ASTParser.K_EXPRESSION, text.strip()).createAST(null);
        return parsed instanceof Expression expression && !isDamaged(expression) ? expression : null;
    }

    /**
     * {@code text} as a type ({@code int}, {@code List<String>}, {@code Point[]}), or {@code null}. Read as the
     * type of a cast, which is the one expression whose grammar holds any type at all.
     */
    public static Type type(String text) {
        if (text == null || text.isBlank() || text.contains("(") || text.contains(")")) return null;
        Expression cast = expression("(" + text.strip() + ") null");
        return cast instanceof CastExpression c ? c.getType() : null;
    }

    /** Whether the parser marked any node under {@code root} as made up or broken. */
    public static boolean isDamaged(ASTNode root) {
        boolean[] damaged = {false};
        root.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) damaged[0] = true;
            }
        });
        return damaged[0];
    }
}
