package com.botmaker.studio.project.managed;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.params.JavaParameterSource;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one rewrite a plugin's window performs on the file it shipped — one source in, one source out.
 *
 * <p><b>One expression, and that is the whole write surface.</b> {@link ASTRewrite} replaces the node the
 * {@code return} holds and copies the rest of the file through untouched, so the comments, the javadoc, the
 * helper methods and the author's own formatting survive a save, and a {@code git diff} after drawing a flow
 * is one hunk. Nothing here adds a method, deletes one or reorders anything: the file is the user's from the
 * moment it was copied in.
 *
 * <p><b>Pure, like {@code JavaParameterEdits}</b>, and it answers the source unchanged whenever it cannot do
 * what was asked — no such class, no such method, a body that is not a single return. The caller compares
 * and reports; nothing here throws, because a window that takes the project down with it is worse than one
 * that declines a click.
 */
public final class JavaManagedEdits {

    private JavaManagedEdits() {}

    /**
     * Replaces the expression {@code className.methodName()} returns with {@code expression}'s tree, adding
     * the imports it names. One already imported, or in the file's own package, is skipped.
     *
     * <p>No expression answers the source unchanged: a method that returns nothing is not something this can
     * write, and emptying one is not an edit anybody asked for.
     */
    public static String setValue(String source, String className, String methodName, JavaValue expression) {
        if (expression == null) return source;
        CompilationUnit unit = JavaParameterSource.parse(source);
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        boolean[] found = {false};

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (!method.getName().getIdentifier().equals(methodName)) return false;
                if (!className.equals(JavaParameterSource.enclosingTypeName(method))) return false;
                if (JavaManagedSource.returnedExpression(method) == null) return false;
                ReturnStatement returned = (ReturnStatement) method.getBody().statements().getFirst();
                found[0] = true;
                rewrite.set(returned, ReturnStatement.EXPRESSION_PROPERTY, expression.copyInto(ast), null);
                return false;
            }
        });
        if (!found[0]) return source;
        addImports(unit, ast, rewrite, expression.imports());
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /**
     * Adds the imports {@code names} asks for that the file does not already have.
     *
     * <p>Appended in the order the catalog gave them rather than sorted into place: the file belongs to its
     * author, and re-sorting their import block to insert one line is a diff about something nobody asked
     * to change. A name in the file's own package, one already imported, and one covered by an on-demand
     * import are all already resolvable, so none is written.
     */
    private static void addImports(CompilationUnit unit, AST ast, ASTRewrite rewrite, List<String> names) {
        if (names == null || names.isEmpty()) return;
        Set<String> have = new LinkedHashSet<>();
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isStatic()) continue;
            String name = imported.getName().getFullyQualifiedName();
            have.add(imported.isOnDemand() ? name + ".*" : name);
        }
        String ownPackage = unit.getPackage() == null ? ""
                : unit.getPackage().getName().getFullyQualifiedName();
        ListRewrite list = rewrite.getListRewrite(unit, CompilationUnit.IMPORTS_PROPERTY);
        for (String name : names) {
            if (name == null || name.isBlank() || name.indexOf('.') < 0) continue;
            String owner = name.substring(0, name.lastIndexOf('.'));
            if (have.contains(name) || have.contains(owner + ".*") || owner.equals(ownPackage)) continue;
            // java.lang is imported by the language itself; writing it is noise in every file it lands in.
            if (owner.equals("java.lang")) continue;
            have.add(name);
            ImportDeclaration declaration = ast.newImportDeclaration();
            declaration.setName(ast.newName(name));
            list.insertLast(declaration, null);
        }
    }
}
