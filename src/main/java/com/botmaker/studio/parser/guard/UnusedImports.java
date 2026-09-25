package com.botmaker.studio.parser.guard;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The imports a file no longer needs, taken out — run on every canvas edit, so deleting the last block that
 * named {@code ArrayList} takes {@code import java.util.ArrayList;} with it.
 *
 * <p>Whether an import is used is JDT's answer ({@link IProblem#UnusedImport}), which needs bindings: without
 * a classpath every import looks unused, so a {@link BotParser#SYNTAX} parser removes nothing. Only a
 * single-type, non-static import is removed. An on-demand {@code java.util.*} or a static import is the
 * user's shorthand, and an import that does not resolve is a compile error for the user to read, not an
 * unused line.
 */
public record UnusedImports(BotParser parser, Path file) {

    public UnusedImports {
        Objects.requireNonNull(parser, "parser");
    }

    /** {@code code} without the imports it does not use; {@code code} itself when there are none. */
    public String removeFrom(String code) {
        if (code == null || !parser.binds()) return code;
        CompilationUnit cu = parser.parse(file, code);
        List<ImportDeclaration> unused = unused(cu);
        if (unused.isEmpty()) return code;
        ASTRewrite rewrite = ASTRewrite.create(cu.getAST());
        ListRewrite imports = rewrite.getListRewrite(cu, CompilationUnit.IMPORTS_PROPERTY);
        for (ImportDeclaration declaration : unused) imports.remove(declaration, null);
        return AstRewriteHelper.applyRewrite(rewrite, code);
    }

    private static List<ImportDeclaration> unused(CompilationUnit cu) {
        List<ImportDeclaration> out = new ArrayList<>();
        for (IProblem problem : cu.getProblems()) {
            if (problem.getID() != IProblem.UnusedImport) continue;
            for (Object o : cu.imports()) {
                ImportDeclaration declaration = (ImportDeclaration) o;
                if (declaration.isStatic() || declaration.isOnDemand() || out.contains(declaration)) continue;
                int start = declaration.getStartPosition();
                if (problem.getSourceStart() >= start
                        && problem.getSourceStart() < start + declaration.getLength()) {
                    out.add(declaration);
                }
            }
        }
        return out;
    }
}
