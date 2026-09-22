package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * Replaces an expression slot with a parsed Java <em>expression snippet</em> (e.g.
 * {@code com.example.plugin.CaptureSource.screen()}). Used where the exact source text is known up
 * front — a plugin's slot editor emits a fully-qualified inline call, which is simpler and more robust than
 * building the equivalent {@code MethodInvocation} AST by hand (a fully-qualified name needs no import mgmt).
 */
public final class RawExpressionHandler {

    private RawExpressionHandler() {}

    public static String replaceWithExpression(CompilationUnit cu, String originalCode,
                                               Expression toReplace, String exprCode) {
        return replaceWithExpression(cu, originalCode, toReplace, exprCode, java.util.List.of());
    }

    /**
     * As above, additionally importing {@code importFqn} — for a snippet that names a type by its
     * <em>simple</em> name ({@code Precision.TIGHT}). The fully-qualified alternative needs no import but puts
     * {@code com.example.plugin.Precision.TIGHT} in the user's source, and these two read as
     * documentation at the call site or they are not worth being types at all.
     */
    public static String replaceWithExpression(CompilationUnit cu, String originalCode,
                                               Expression toReplace, String exprCode, String importFqn) {
        return replaceWithExpression(cu, originalCode, toReplace, exprCode,
                importFqn == null ? java.util.List.of() : java.util.List.of(importFqn));
    }

    /**
     * As above with any number of imports, in <b>one</b> rewrite. A value written through the host's grammar
     * can name several types — {@code new Rect(new Point(0, 0), …)} — and one rewrite per import would hand
     * the second a node the first had already replaced.
     */
    public static String replaceWithExpression(CompilationUnit cu, String originalCode, Expression toReplace,
                                               String exprCode, java.util.List<String> importFqns) {
        if (exprCode == null || exprCode.isBlank()) return originalCode;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(exprCode.toCharArray());
        ASTNode parsed = parser.createAST(null);
        if (!(parsed instanceof Expression parsedExpr)) return originalCode;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Expression copied = (Expression) ASTNode.copySubtree(ast, parsedExpr);
        rewriter.replace(toReplace, copied, null);
        for (String importFqn : importFqns) {
            if (importFqn != null && !importFqn.isBlank()) ImportManager.addImport(cu, rewriter, importFqn);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
