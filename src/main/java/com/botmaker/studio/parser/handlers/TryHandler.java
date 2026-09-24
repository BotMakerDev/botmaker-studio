package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The edits a {@code try} block offers beyond its bodies: add or remove a {@code catch}, add or remove the
 * {@code finally}, and change what a {@code catch} catches.
 *
 * <p>Every one keeps the statement legal. A {@code try} needs a {@code catch}, a {@code finally} or a
 * resource, so the removal that would leave it with none is refused — {@code null}, which the caller turns into
 * "nothing happened" — rather than written and left for the compiler to reject.
 */
public final class TryHandler {

    private TryHandler() {}

    /** The exception a new {@code catch} catches — every checked and unchecked one a bot's call can throw. */
    public static final String DEFAULT_CATCH_TYPE = "Exception";

    /** Appends {@code catch (Exception e) { }}, named so it shadows nothing in scope. */
    public static String addCatch(EditContext ctx, String originalCode, TryStatement tryStmt) {
        AST ast = ctx.ast();
        CatchClause clause = ast.newCatchClause();
        SingleVariableDeclaration param = ast.newSingleVariableDeclaration();
        param.setType(ast.newSimpleType(ast.newSimpleName(DEFAULT_CATCH_TYPE)));
        param.setName(ast.newSimpleName(freeCatchName(tryStmt)));
        clause.setException(param);
        ctx.rewriter().getListRewrite(tryStmt, TryStatement.CATCH_CLAUSES_PROPERTY).insertLast(clause, null);
        return ctx.applyTo(originalCode);
    }

    /** Removes the {@code index}th {@code catch}, or {@code null} when it is the last thing making the try legal. */
    public static String deleteCatch(EditContext ctx, String originalCode, TryStatement tryStmt, int index) {
        List<?> clauses = tryStmt.catchClauses();
        if (index < 0 || index >= clauses.size()) return null;
        if (clauses.size() == 1 && tryStmt.getFinally() == null && tryStmt.resources().isEmpty()) return null;
        ctx.rewriter().getListRewrite(tryStmt, TryStatement.CATCH_CLAUSES_PROPERTY)
                .remove((ASTNode) clauses.get(index), null);
        return ctx.applyTo(originalCode);
    }

    /** Adds an empty {@code finally}, or {@code null} when there already is one. */
    public static String addFinally(EditContext ctx, String originalCode, TryStatement tryStmt) {
        if (tryStmt.getFinally() != null) return null;
        ctx.rewriter().set(tryStmt, TryStatement.FINALLY_PROPERTY, ctx.ast().newBlock(), null);
        return ctx.applyTo(originalCode);
    }

    /** Removes the {@code finally}, or {@code null} when nothing else would keep the try legal. */
    public static String deleteFinally(EditContext ctx, String originalCode, TryStatement tryStmt) {
        if (tryStmt.getFinally() == null) return null;
        if (tryStmt.catchClauses().isEmpty() && tryStmt.resources().isEmpty()) return null;
        ctx.rewriter().set(tryStmt, TryStatement.FINALLY_PROPERTY, null, null);
        return ctx.applyTo(originalCode);
    }

    /**
     * Makes {@code clause} catch {@code typeText} — one name, or several separated by {@code |} (a
     * multi-catch). Each simple name is imported the way a pasted one is. {@code null} when the text is not
     * a list of type names.
     */
    public static String setCatchType(EditContext ctx, String originalCode, CatchClause clause, String typeText) {
        List<String> names = catchTypeNames(typeText);
        if (names.isEmpty()) return null;
        AST ast = ctx.ast();
        Type type;
        if (names.size() == 1) {
            type = simpleType(ctx, names.getFirst());
        } else {
            UnionType union = ast.newUnionType();
            for (String name : names) union.types().add(simpleType(ctx, name));
            type = union;
        }
        ctx.rewriter().set(clause.getException(), SingleVariableDeclaration.TYPE_PROPERTY, type, null);
        return ctx.applyTo(originalCode);
    }

    /** What a {@code catch} names, as written: {@code IOException | InterruptedException}. */
    public static String catchTypeText(CatchClause clause) {
        return clause.getException().getType().toString().replaceAll("\\s*\\|\\s*", " | ");
    }

    /** {@code typeText} split at {@code |}, each part a (possibly qualified) Java name — or empty when one isn't. */
    static List<String> catchTypeNames(String typeText) {
        List<String> out = new ArrayList<>();
        if (typeText == null || typeText.isBlank()) return out;
        for (String part : typeText.split("\\|")) {
            String name = part.strip();
            if (!isQualifiedName(name)) return List.of();
            out.add(name);
        }
        return out;
    }

    private static boolean isQualifiedName(String text) {
        if (text.isEmpty()) return false;
        for (String segment : text.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) return false;
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) return false;
            }
        }
        return true;
    }

    private static Type simpleType(EditContext ctx, String name) {
        AST ast = ctx.ast();
        if (name.indexOf('.') < 0) ctx.addImportForSimpleName(name);
        Name typeName = ast.newName(name);
        return ast.newSimpleType(typeName);
    }

    /**
     * {@code e}, or {@code e2}, {@code e3}… when {@code e} is already a variable the new clause could see. A
     * sibling {@code catch}'s own parameter does not count: its scope is its clause, which is why every
     * {@code catch} in Java can be {@code e}.
     */
    private static String freeCatchName(TryStatement tryStmt) {
        Set<String> taken = new HashSet<>();
        ASTNode scope = tryStmt;
        while (scope.getParent() != null && !(scope instanceof MethodDeclaration)) scope = scope.getParent();
        scope.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                taken.add(node.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                if (!(node.getParent() instanceof CatchClause clause) || encloses(clause, tryStmt)) {
                    taken.add(node.getName().getIdentifier());
                }
                return true;
            }
        });
        if (!taken.contains("e")) return "e";
        for (int i = 2; ; i++) {
            if (!taken.contains("e" + i)) return "e" + i;
        }
    }

    private static boolean encloses(ASTNode outer, ASTNode inner) {
        for (ASTNode n = inner; n != null; n = n.getParent()) {
            if (n == outer) return true;
        }
        return false;
    }
}
