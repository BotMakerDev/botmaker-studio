package com.botmaker.studio.parser;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.CodeBlock;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ChildPropertyDescriptor;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Javadoc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Decides which blocks survive a re-parse, and re-points the ones that do at the tree that replaced theirs.
 *
 * <p>Every edit re-parses the whole file, and until this existed every block object and every JavaFX
 * {@code Node} in it was thrown away and rebuilt. That is why {@code EditorCanvas} restores its scroll
 * position by hand and why {@code ProgramShapeOverlay} keeps a "focus this after the next update" field: the
 * identity of a drawn block did not survive the edit that redrew it.
 *
 * <p><b>The unit is the subtree, and that is what makes this buildable.</b> {@link BlockConverter} offers
 * reuse at its three choke points — {@code parseStatement}, {@code parseBodyBlock}, {@code parseExpression} —
 * so a parent whose own text changed is rebuilt, builds its children the only way it ever has, and is handed
 * the survivor. No container block is edited, no {@code replaceChild} interface is added, and no per-block
 * field is written from outside the block that owns it.
 *
 * <p>Pure apart from the blocks it re-points: it draws nothing and starts no toolkit, so every refusal below
 * is assertable headlessly. See {@code docs/refactor/30-block-reuse.md}.
 */
public final class BlockReuse {

    /**
     * Reuse switched off — the default, and what every caller that has not opted in passes.
     *
     * <p>A parse under this instance is byte for byte the parse this project has always done, which is what
     * makes the existing suite the regression guard for the whole feature.
     */
    public static final BlockReuse NONE = new BlockReuse(Map.of(), null, false, block -> false);

    private final Map<ASTNode, CodeBlock> previous;
    private final Map<String, CodeBlock> byId;
    private final String previousSource;
    private final boolean previousReadOnly;
    private final Predicate<CodeBlock> policy;

    private BlockReuse(Map<ASTNode, CodeBlock> previous, String previousSource, boolean previousReadOnly,
                       Predicate<CodeBlock> policy) {
        this.previous = previous;
        this.previousSource = previousSource;
        this.previousReadOnly = previousReadOnly;
        this.policy = policy;
        this.byId = new HashMap<>();
        for (CodeBlock block : previous.values()) {
            // Last writer wins, and there is never a contest: a BlockId is a structural path, so two blocks
            // in one tree cannot share one. A Comment is the exception -- it has no path and falls back to a
            // positional encoding -- and comments are never taken directly, only adopted alongside the
            // subtree that contains them.
            byId.put(block.getId(), block);
        }
    }

    /**
     * An oracle over the tree that is about to be replaced.
     *
     * @param previous         the outgoing {@code ASTNode -> CodeBlock} registry, read before it is cleared
     * @param previousSource   the source those nodes' offsets index into — <b>not</b> the new source
     * @param previousReadOnly the lock verdict those blocks were parsed under, so a parse whose verdict
     *                         differs can refuse reuse wholesale; see {@link #take}
     * @param policy           which surviving blocks this parse is willing to keep; see
     *                         {@code docs/refactor/30-block-reuse.md} §7, where the narrow phase allows one
     *                         subtree and the wide phase allows everything
     */
    public static BlockReuse of(Map<ASTNode, CodeBlock> previous, String previousSource,
                                boolean previousReadOnly, Predicate<CodeBlock> policy) {
        if (previous == null || previous.isEmpty() || previousSource == null || policy == null) return NONE;
        return new BlockReuse(Map.copyOf(previous), previousSource, previousReadOnly, policy);
    }

    /**
     * The block that may keep {@code newNode}, or null to build a fresh one.
     *
     * <p>Five refusals, each one a way reuse could edit the <b>wrong code</b> rather than merely fail to
     * help: the file's lock verdict moved; nothing was drawn at this path; the text there changed; this
     * parse's policy declines; or the previous block has no node to compare against.
     *
     * <p><b>The lock verdict is checked first and refuses every subtree, not one.</b> A survivor keeps the
     * {@code isReadOnly} it was parsed with — {@link BlockConverter} stamps that verdict on a block it
     * <em>builds</em>, and a block handed back here is never built — so a re-parse that toggled reader mode,
     * or that switched a file between the user's own source and bundled library source, would otherwise draw
     * an editable block over code the parse had just declared locked. Re-stamping the survivor instead would
     * be half a fix: {@code applyReadOnly} only ever sets the flag, so it could lock a block and never unlock
     * one. See {@code docs/refactor/30-block-reuse.md} §10.
     */
    public CodeBlock take(ASTNode newNode, ParseContext ctx) {
        if (this == NONE || newNode == null || ctx == null) return null;

        if (ctx.readOnly() != previousReadOnly) return null;

        CodeBlock old = byId.get(BlockId.of(newNode));
        if (old == null) return null;

        ASTNode oldNode = old.getAstNode();
        if (oldNode == null) return null;

        String before = slice(previousSource, oldNode);
        String after = slice(ctx.sourceCode(), newNode);
        if (before == null || !before.equals(after)) return null;

        if (!sameSlot(oldNode, newNode, ctx)) return null;

        if (!policy.test(old)) return null;

        adopt(oldNode, newNode, ctx);
        adoptComments(oldNode, newNode, ctx);
        return old;
    }

    /**
     * Whether an expression still sits in the slot it was drawn for: its parent's text is unchanged too. A
     * statement answers yes: a line is drawn the same in any body.
     *
     * <p><b>An expression is drawn for its slot, not only for its own text</b> (2026-09-26). Its editor is chosen
     * by the call it is an argument of and the type that call's parameter expects — a plugin's slot editor asks
     * for the enclosing {@code Executable}. Switching {@code Wait.seconds(1)} to {@code Wait.milliseconds(1)}
     * leaves {@code 1} untouched, so the argument survived and went on being drawn as {@code seconds}' slot; as
     * soon as a switch changed the argument's text too, the next one worked, which is why it looked
     * intermittent. Rebuilding an expression whose parent changed costs a few widgets on the one line edited.
     */
    private boolean sameSlot(ASTNode oldNode, ASTNode newNode, ParseContext ctx) {
        if (!(newNode instanceof Expression)) return true;
        ASTNode oldParent = oldNode.getParent();
        ASTNode newParent = newNode.getParent();
        if (oldParent == null || newParent == null) return oldParent == newParent;
        if (oldNode.getLocationInParent() != newNode.getLocationInParent()) return false;
        String before = slice(previousSource, oldParent);
        return before != null && before.equals(slice(ctx.sourceCode(), newParent));
    }

    /** {@code node}'s own source text, or null when the offsets do not index into {@code source}. */
    private static String slice(String source, ASTNode node) {
        if (source == null) return null;
        int start = node.getStartPosition();
        int length = node.getLength();
        if (start < 0 || length < 0 || start + length > source.length()) return null;
        return source.substring(start, start + length);
    }

    /**
     * Re-points every block of the old subtree at its counterpart in the new one, and registers it there.
     *
     * <p>A lockstep walk rather than a lookup by id: {@link #take} has already established that the two
     * subtrees carry identical source text, so they are structurally identical and their children line up
     * position for position. That is cheaper than recomputing a structural path per node, and it cannot pair
     * two nodes that are not counterparts.
     */
    private void adopt(ASTNode oldNode, ASTNode newNode, ParseContext ctx) {
        CodeBlock block = previous.get(oldNode);
        if (block instanceof AbstractCodeBlock concrete) {
            concrete.adopt(newNode);
            ctx.nodeToBlockMap().put(newNode, block);
        }

        List<ASTNode> oldChildren = structuralChildren(oldNode);
        List<ASTNode> newChildren = structuralChildren(newNode);
        // Cannot differ -- identical text parses to identical structure -- so this is a refusal to guess
        // rather than a case to handle. Stopping leaves the blocks above already adopted, which is correct:
        // they were matched on their own text.
        if (oldChildren.size() != newChildren.size()) return;
        for (int i = 0; i < oldChildren.size(); i++) {
            adopt(oldChildren.get(i), newChildren.get(i), ctx);
        }
    }

    /**
     * The same for the comments inside the subtree, which the walk above cannot reach.
     *
     * <p>A {@link Comment} hangs off {@code CompilationUnit.getCommentList()} rather than off the tree, so it
     * is nobody's structural child and {@link BlockId} falls back to a positional encoding for it. Pairing by
     * id would therefore miss as soon as the subtree's offset shifted — which is exactly what an edit above
     * it does. Pairing by <b>order within the subtree</b> cannot: the text is identical, so the comments in
     * it are the same comments in the same sequence.
     */
    private void adoptComments(ASTNode oldNode, ASTNode newNode, ParseContext ctx) {
        List<Comment> before = commentsIn(oldNode, commentsOf(oldNode));
        List<Comment> after = commentsIn(newNode, ctx.comments());
        if (before.size() != after.size()) return;
        for (int i = 0; i < before.size(); i++) {
            CodeBlock block = previous.get(before.get(i));
            if (block instanceof AbstractCodeBlock concrete) {
                concrete.adopt(after.get(i));
                ctx.nodeToBlockMap().put(after.get(i), block);
            }
        }
    }

    /** Non-Javadoc comments of the compilation unit {@code node} belongs to. */
    private static List<Comment> commentsOf(ASTNode node) {
        if (!(node.getRoot() instanceof CompilationUnit cu)) return List.of();
        List<Comment> out = new ArrayList<>();
        for (Object each : cu.getCommentList()) {
            if (each instanceof Comment comment && !(each instanceof Javadoc)) out.add(comment);
        }
        return out;
    }

    /** Those of {@code comments} that fall inside {@code node}'s own extent, in source order. */
    private static List<Comment> commentsIn(ASTNode node, List<Comment> comments) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        List<Comment> out = new ArrayList<>();
        for (Comment comment : comments) {
            int at = comment.getStartPosition();
            if (at >= start && at < end) out.add(comment);
        }
        out.sort(Comparator.comparingInt(ASTNode::getStartPosition));
        return out;
    }

    /** {@code node}'s child nodes, in declaration order, single children and list children alike. */
    private static List<ASTNode> structuralChildren(ASTNode node) {
        List<ASTNode> out = new ArrayList<>();
        for (Object property : node.structuralPropertiesForType()) {
            if (property instanceof ChildPropertyDescriptor child) {
                if (node.getStructuralProperty(child) instanceof ASTNode value) out.add(value);
            } else if (property instanceof ChildListPropertyDescriptor children) {
                for (Object value : (List<?>) node.getStructuralProperty(children)) {
                    if (value instanceof ASTNode each) out.add(each);
                }
            }
        }
        return out;
    }
}
