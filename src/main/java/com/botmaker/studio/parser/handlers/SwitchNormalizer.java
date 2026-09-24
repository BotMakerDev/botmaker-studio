package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EmptyStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts every {@code switch} case into the shape the editor renders — a terminating {@code break} on the colon
 * form, a braced body on the arrow form.
 *
 * <p>Studio renders a case's trailing {@code break} as case chrome, not as a deletable child block — so in the
 * editor fall-through cannot be created. Source can still arrive without one (hand-edited, or written outside
 * Studio), and then the chrome would be claiming something the code doesn't do. Normalising on open keeps the
 * two honest, and eliminates the class of bug fall-through causes for users who didn't intend it.
 *
 * <p>Two shapes are deliberately left alone. An <b>empty</b> case chunk ({@code case A: case B: …}) is the
 * multi-label idiom — inserting a break there would change what the code means, not tidy it. And a chunk already
 * ending in {@code return}/{@code throw}/{@code continue} can't fall through either, so it needs nothing.
 *
 * <p>A third pass, since 2026-09-24, braces the bare one-statement body of an {@code if}, {@code else}, loop
 * or classic {@code for} — the arrow-rule reasoning applied to every block that draws a body.
 *
 * <p>All passes <b>rewrite the user's source when the file is opened</b>. That is a real edit, not a display
 * convenience, and it is the price of the editor's own honesty: a control it draws as fixed chrome has to be
 * backed by something in the source, and a branch it offers as a drop target has to have somewhere to drop
 * into. The break pass established the precedent; the brace pass follows it.
 */
public final class SwitchNormalizer {

    private SwitchNormalizer() {}

    /**
     * Both passes in one rewrite, so opening a file is at most one edit and one undo entry.
     *
     * @return the normalised source, or {@code null} when nothing needed changing (the overwhelmingly common
     *         case — callers skip the edit entirely).
     */
    public static String normalize(CompilationUnit cu, String originalCode) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        boolean[] changed = {false};

        cu.accept(new ASTVisitor() {
            // The third pass is not about switches: a loop or an if whose body is one bare statement
            // (`if (done) return;`) gets braces too, for the same reason an arrow rule does — its block draws a
            // body to drop into, and without a Block behind it there is nowhere for the drop to go. Before
            // 2026-09-24 the converter simply left such a body out, so the statement vanished from the canvas.
            @Override
            public boolean visit(IfStatement node) {
                if (braceBody(ast, rewriter, node.getThenStatement())) changed[0] = true;
                // An `else if` is the chain the if block draws flat; bracing it would nest it one level deeper.
                Statement elseStatement = node.getElseStatement();
                if (!(elseStatement instanceof IfStatement) && braceBody(ast, rewriter, elseStatement)) {
                    changed[0] = true;
                }
                return true;
            }

            @Override
            public boolean visit(WhileStatement node) {
                if (braceBody(ast, rewriter, node.getBody())) changed[0] = true;
                return true;
            }

            @Override
            public boolean visit(DoStatement node) {
                if (braceBody(ast, rewriter, node.getBody())) changed[0] = true;
                return true;
            }

            @Override
            public boolean visit(ForStatement node) {
                if (braceBody(ast, rewriter, node.getBody())) changed[0] = true;
                return true;
            }

            @Override
            public boolean visit(EnhancedForStatement node) {
                if (braceBody(ast, rewriter, node.getBody())) changed[0] = true;
                return true;
            }

            @Override
            public boolean visit(SwitchStatement node) {
                // The two forms are exclusive, and each pass is meaningless on the other: an arrow rule can't
                // fall through, and a colon case has no single body to brace.
                if (usesArrowLabels(node)) {
                    if (braceRuleBodies(ast, rewriter, node)) changed[0] = true;
                    return true;
                }
                if (appendMissingBreaks(ast, rewriter, node)) changed[0] = true;
                return true;
            }
        });

        return changed[0] ? AstRewriteHelper.applyRewrite(rewriter, originalCode) : null;
    }

    /** A {@code break} on every falling-through case of a colon-form switch. */
    private static boolean appendMissingBreaks(AST ast, ASTRewrite rewriter, SwitchStatement node) {
        boolean changed = false;
        ListRewrite list = rewriter.getListRewrite(node, SwitchStatement.STATEMENTS_PROPERTY);
        for (List<Statement> chunk : caseChunks(node)) {
            // chunk[0] is the SwitchCase label itself; anything after it is the case body.
            if (chunk.size() <= 1) continue;
            Statement last = chunk.getLast();
            if (terminates(last)) continue;
            list.insertAfter(ast.newBreakStatement(), last, null);
            changed = true;
        }
        return changed;
    }

    /**
     * Braces every arrow rule whose body is a bare statement, so {@code case X -> foo();} becomes
     * {@code case X -> { foo(); }}.
     *
     * <p>Without it that branch has no {@link org.eclipse.jdt.core.dom.Block} for a dropped block to go into,
     * and the insert falls back to the colon-form anchor — which writes the new statement in front of the rule
     * instead of inside it. Wrapping is always legal here: {@code SwitchStatement} is a statement switch, so a
     * rule body is a statement and a block is one too. A {@code SwitchExpression}'s {@code case X -> value;}
     * would need a {@code yield} and is a different JDT node this visitor never sees.
     */
    private static boolean braceRuleBodies(AST ast, ASTRewrite rewriter, SwitchStatement node) {
        boolean changed = false;
        List<?> statements = node.statements();
        for (int i = 0; i < statements.size() - 1; i++) {
            if (!(statements.get(i) instanceof SwitchCase sc) || !sc.isSwitchLabeledRule()) continue;
            if (!(statements.get(i + 1) instanceof Statement body) || body instanceof Block) continue;
            // A label can't be its own rule's body; that reads as an empty rule and bracing it would be a
            // change of meaning rather than a tidy-up.
            if (body instanceof SwitchCase) continue;
            Block braced = ast.newBlock();
            braced.statements().add(ASTNode.copySubtree(ast, body));
            rewriter.replace(body, braced, null);
            changed = true;
        }
        return changed;
    }

    /**
     * Wraps {@code body} in braces when it is a bare statement. A move target rather than a copy, so a switch
     * inside the body that the other passes are editing in the same rewrite keeps those edits. An empty
     * statement ({@code while (poll());}) becomes an empty block rather than a block holding {@code ;}.
     */
    private static boolean braceBody(AST ast, ASTRewrite rewriter, Statement body) {
        if (body == null || body instanceof Block) return false;
        Block braced = ast.newBlock();
        if (!(body instanceof EmptyStatement)) braced.statements().add(rewriter.createMoveTarget(body));
        rewriter.replace(body, braced, null);
        return true;
    }

    private static boolean usesArrowLabels(SwitchStatement node) {
        for (Object o : node.statements()) {
            if (o instanceof SwitchCase sc && sc.isSwitchLabeledRule()) return true;
        }
        return false;
    }

    /** The switch's statement list split at each {@code case}/{@code default} label. */
    private static List<List<Statement>> caseChunks(SwitchStatement node) {
        List<List<Statement>> chunks = new ArrayList<>();
        List<Statement> current = null;
        for (Object o : node.statements()) {
            Statement s = (Statement) o;
            if (s instanceof SwitchCase) {
                current = new ArrayList<>();
                chunks.add(current);
            }
            if (current != null) current.add(s);
        }
        return chunks;
    }

    /** Whether control provably leaves the case at {@code s} — no {@code break} needed after it. */
    private static boolean terminates(Statement s) {
        return s instanceof BreakStatement || s instanceof ReturnStatement
                || s instanceof ThrowStatement || s instanceof ContinueStatement;
    }
}
