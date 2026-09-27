package com.botmaker.studio.services.debug;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.List;

/**
 * Whether a name on the canvas is the paused frame's local of that name (2026-09-27): the local it refers to
 * is declared, and its scope is still open, at the paused line.
 *
 * <p>Found by walking the tree, not through a binding: the editor's tree is routinely unbound (a project whose
 * classpath has not resolved yet), and the chip must not then say nothing. The walk is Java's own scoping for
 * locals — earlier statements of each enclosing block, a {@code for}'s initialisers, a loop, lambda or catch
 * parameter, the method's parameters — and stops at the method: past it a name is a field, which is not a local
 * of the frame and gets no chip. Two sibling locals of one name are told apart because only one of their
 * blocks holds the paused line.
 */
public final class LiveScope {

    private LiveScope() {}

    /** A local's declaration and the node its scope spans. */
    private record Local(VariableDeclaration declaration, ASTNode scope) {}

    /**
     * The names a block's chip speaks for: each variable a declaration declares, the variable an assignment
     * sets, or a name block itself. A name that is the target of an assignment is left to the assignment's
     * chip, so one value is not shown twice on one line.
     *
     * @param expressionBlock whether the block is drawn as an expression (a name block), not a statement
     */
    public static List<SimpleName> shownBy(ASTNode node, boolean expressionBlock) {
        return switch (node) {
            case VariableDeclarationStatement declared -> declared.fragments().stream()
                    .map(f -> ((VariableDeclarationFragment) f).getName()).toList();
            case ExpressionStatement statement when statement.getExpression() instanceof Assignment set
                    && set.getLeftHandSide() instanceof SimpleName target -> List.of(target);
            case SimpleName name when expressionBlock
                    && !(name.getParent() instanceof Assignment set && set.getLeftHandSide() == name)
                    && !(name.getParent() instanceof VariableDeclaration d && d.getName() == name) -> List.of(name);
            case null, default -> List.of();
        };
    }

    /** Whether {@code name} is a local that holds a value at {@code pausedLine}. */
    public static boolean isLive(SimpleName name, CompilationUnit cu, int pausedLine) {
        Local local = localOf(name);
        if (local == null || local.scope() == null) return false;
        int from = cu.getLineNumber(local.scope().getStartPosition());
        int to = cu.getLineNumber(local.scope().getStartPosition() + local.scope().getLength() - 1);
        if (pausedLine < from || pausedLine > to) return false;
        // A parameter holds its value from the first line; a local only once its declaration has run, which
        // a pause on its own line has not yet done.
        if (isParameter(local.declaration())) return true;
        return cu.getLineNumber(local.declaration().getStartPosition()) < pausedLine;
    }

    private static boolean isParameter(VariableDeclaration declaration) {
        ASTNode parent = declaration.getParent();
        return parent instanceof MethodDeclaration || parent instanceof LambdaExpression
                || parent instanceof CatchClause;
    }

    private static Local localOf(SimpleName name) {
        ASTNode parent = name.getParent();
        if (parent instanceof VariableDeclaration declaration && declaration.getName() == name) {
            return new Local(declaration, scopeOf(declaration));
        }
        // Not a variable read at all: a member's name, or the right half of `a.b`.
        if (parent instanceof FieldAccess access && access.getName() == name) return null;
        if (parent instanceof QualifiedName qualified && qualified.getName() == name) return null;
        if (parent instanceof MethodInvocation call && call.getName() == name) return null;

        String id = name.getIdentifier();
        ASTNode child = name;
        for (ASTNode at = parent; at != null; child = at, at = at.getParent()) {
            switch (at) {
                case Block block -> {
                    Local found = before(block.statements(), child, id, block);
                    if (found != null) return found;
                }
                case SwitchStatement cases -> {
                    Local found = before(cases.statements(), child, id, cases);
                    if (found != null) return found;
                }
                case ForStatement loop -> {
                    for (Object init : loop.initializers()) {
                        if (init instanceof VariableDeclarationExpression e) {
                            VariableDeclarationFragment f = named(e.fragments(), id);
                            if (f != null) return new Local(f, loop);
                        }
                    }
                }
                case EnhancedForStatement loop -> {
                    if (child != loop.getExpression() && loop.getParameter().getName().getIdentifier().equals(id)) {
                        return new Local(loop.getParameter(), loop);
                    }
                }
                case LambdaExpression lambda -> {
                    for (Object p : lambda.parameters()) {
                        if (p instanceof VariableDeclaration d && d.getName().getIdentifier().equals(id)) {
                            return new Local(d, lambda);
                        }
                    }
                }
                case CatchClause caught -> {
                    if (child == caught.getBody() && caught.getException().getName().getIdentifier().equals(id)) {
                        return new Local(caught.getException(), caught);
                    }
                }
                case MethodDeclaration method -> {
                    for (Object p : method.parameters()) {
                        SingleVariableDeclaration d = (SingleVariableDeclaration) p;
                        if (d.getName().getIdentifier().equals(id)) return new Local(d, method.getBody());
                    }
                    return null;
                }
                case AbstractTypeDeclaration ignored -> {
                    return null;
                }
                case AnonymousClassDeclaration ignored -> {
                    return null;
                }
                default -> { }
            }
        }
        return null;
    }

    /** A local of {@code id} declared by one of {@code statements} before the one holding {@code child}. */
    private static Local before(List<?> statements, ASTNode child, String id, ASTNode scope) {
        for (Object s : statements) {
            if (s == child) break;
            if (s instanceof VariableDeclarationStatement declared) {
                VariableDeclarationFragment f = named(declared.fragments(), id);
                if (f != null) return new Local(f, scope);
            }
        }
        return null;
    }

    private static VariableDeclarationFragment named(List<?> fragments, String id) {
        for (Object f : fragments) {
            if (f instanceof VariableDeclarationFragment fragment && fragment.getName().getIdentifier().equals(id)) {
                return fragment;
            }
        }
        return null;
    }

    /** What a declaration's scope spans: the rest of its block, its loop, its lambda, catch or method body. */
    private static ASTNode scopeOf(VariableDeclaration declaration) {
        ASTNode parent = declaration.getParent();
        return switch (parent) {
            case VariableDeclarationStatement statement -> enclosingScope(statement);
            case VariableDeclarationExpression expression -> expression.getParent();
            case MethodDeclaration method -> method.getBody();
            case null -> null;
            default -> parent;
        };
    }

    private static ASTNode enclosingScope(Statement statement) {
        ASTNode parent = statement.getParent();
        return parent instanceof Block || parent instanceof SwitchStatement ? parent : null;
    }
}
