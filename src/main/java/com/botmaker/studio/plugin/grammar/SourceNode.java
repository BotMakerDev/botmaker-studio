package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;

import java.util.Optional;

/**
 * An expression and the text its offsets index: the file it was parsed out of, or the one expression.
 *
 * <p>A JDT node does not carry the text it came from, and a part the grammar cannot read is shown as it was
 * written. {@code node.toString()} is JDT's own reprint, not what the person wrote. It is used only when
 * {@code text} is {@code null}: a canvas slot, whose {@code ValueSlot.source()} has always shown that.
 */
public record SourceNode(Expression node, String text) {

    /** {@code source} parsed as exactly one expression, or empty. */
    public static Optional<SourceNode> parse(String source) {
        if (source == null || source.isBlank()) return Optional.empty();
        Expression node = JavaExpressions.parse(source);
        if (node == null || (node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) return Optional.empty();
        if (node.getLength() != source.strip().length()) return Optional.empty();
        return Optional.of(new SourceNode(node, source));
    }

    /** The node's own slice of {@link #text}, as it was written. */
    public String source() {
        if (text == null) return node.toString();
        int start = node.getStartPosition();
        return text.substring(start, start + node.getLength());
    }

    /** A node inside this one, over the same text. */
    public SourceNode child(Expression child) {
        return new SourceNode(child, text);
    }
}
