package com.botmaker.studio.parser;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Generates a block id from its backing AST node: the node's <b>structural path</b> from the compilation
 * unit, such as {@code types[0]/bodyDeclarations[2]/body/statements[3]/expression}.
 *
 * <p>Unique within a compilation unit — two distinct nodes cannot occupy one path — and, which is the whole
 * point, <b>stable across a re-parse whenever the block's position in the tree has not moved</b>. An edit in
 * another method, a rename above, a reformatted line, an added comment, a statement appended after this one:
 * none of them changes this id.
 *
 * <p><b>It was {@code <kind>_<startPosition>_<length>} until 2026-09-13, and that failed the one job an id
 * has to do here.</b> Its only reader that crosses a re-parse is the breakpoint set, and every edit is
 * followed by a re-parse: typing one character anywhere above a block shifted its start position, changed
 * its id, and dropped its breakpoint without a word. The old javadoc promised stability "across re-parses of
 * unchanged code", which is true and useless, since the re-parse always follows a change. See
 * {@code docs/refactor/29-block-layer.md} §2.3.
 *
 * <p><b>What still moves an id, correctly:</b> inserting or deleting a <em>preceding sibling</em>, because
 * the block's index in its own list is part of where it is. A breakpoint that followed a statement pushed
 * down the file would need the statement tracked through the edit, which is a rewriter's job and not an id
 * scheme's — so the honest claim is "elsewhere in the file", not "anywhere".
 *
 * <p>A node with no location in its parent — a {@code Comment}, which hangs off {@code cu.getCommentList()}
 * rather than the tree, or a node detached from any root — has no path, and falls back to the old positional
 * encoding. That keeps such nodes distinguishable from each other, which is all they ever needed: nothing
 * executes at a comment, so nothing holds a breakpoint there.
 */
public final class BlockId {

    private BlockId() {}

    public static String of(ASTNode node) {
        if (node == null) return "none";

        Deque<String> segments = new ArrayDeque<>();
        for (ASTNode current = node; current != null; current = current.getParent()) {
            StructuralPropertyDescriptor location = current.getLocationInParent();
            // Null at the compilation unit, and at anything not attached to one.
            if (location == null) break;

            String segment = location.getId();
            if (location.isChildListProperty()) {
                // Which of the siblings this is. Two statements in one block differ only here.
                List<?> siblings = (List<?>) current.getParent().getStructuralProperty(location);
                segment += "[" + siblings.indexOf(current) + "]";
            }
            segments.push(segment);
        }

        return segments.isEmpty() ? positional(node) : String.join("/", segments);
    }

    /**
     * The node {@code id} names in {@code unit}, which is how an edit addresses what it edits: the block's id
     * read against the tree the edit runs on. Empty for an id with no place in that tree, such as a comment's
     * positional one or a path the tree no longer has.
     */
    public static Optional<ASTNode> find(CompilationUnit unit, String id) {
        if (unit == null || id == null || id.isEmpty()) return Optional.empty();
        ASTNode current = unit;
        for (String segment : id.split("/")) {
            int bracket = segment.indexOf('[');
            String property = bracket < 0 ? segment : segment.substring(0, bracket);
            Object value = childProperty(current, property);
            if (bracket >= 0) {
                int index;
                try {
                    index = Integer.parseInt(segment, bracket + 1, segment.length() - 1, 10);
                } catch (NumberFormatException notAPath) {
                    return Optional.empty();
                }
                if (!(value instanceof List<?> siblings) || index < 0 || index >= siblings.size()) {
                    return Optional.empty();
                }
                value = siblings.get(index);
            }
            if (!(value instanceof ASTNode next)) return Optional.empty();
            current = next;
        }
        return Optional.of(current);
    }

    /** The value of {@code node}'s structural property with this id, or {@code null} when it has none. */
    private static Object childProperty(ASTNode node, String id) {
        for (Object descriptor : node.structuralPropertiesForType()) {
            StructuralPropertyDescriptor property = (StructuralPropertyDescriptor) descriptor;
            if (property.getId().equals(id)) return node.getStructuralProperty(property);
        }
        return null;
    }

    /** The pre-2026-09-13 encoding, kept for the nodes that have no place in the tree to describe. */
    private static String positional(ASTNode node) {
        return node.getClass().getSimpleName() + "_" + node.getStartPosition() + "_" + node.getLength();
    }
}
