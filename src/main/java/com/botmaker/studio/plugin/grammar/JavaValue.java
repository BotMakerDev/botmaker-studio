package com.botmaker.studio.plugin.grammar;

import com.botmaker.studio.parser.helpers.SourceFormatter;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A value written as Java: one expression <em>node</em>, and the classes it names by their simple names.
 *
 * <p><b>What this replaced (2026-09-24)</b> is {@code ValueGrammar.Written}, a {@code String} of source that
 * every sink parsed again or placed into its rewrite verbatim through {@code createStringPlaceholder}. The
 * grammar now builds the tree — {@code ast.newClassInstanceCreation()}, not {@code "new " + name + "("} — and
 * a sink copies it into the file's own tree ({@link #copyInto}), where {@code ASTRewrite} lays it out. Nothing
 * on the way from a value to a file is text.
 *
 * <p>{@link #source()} is for <b>showing</b> the value — a cell's label, {@code ValueContext.source()} — and
 * for nothing else: a built node is shown as the formatter lays it out, and an expression kept from a file as
 * its author wrote it.
 *
 * @param imports canonical names of the classes {@code node} writes by simple name, first reached first — each
 *                taken from a {@link Class} or a declaration's own name, never read out of text
 */
public record JavaValue(Expression node, List<String> imports, String source) {

    public JavaValue {
        Objects.requireNonNull(node, "node");
        imports = imports == null ? List.of() : List.copyOf(imports);
        source = source == null ? "" : source;
    }

    /** A node the host built, shown as the formatter lays it out. */
    public static JavaValue built(Expression node, Collection<String> imports) {
        return new JavaValue(node, List.copyOf(imports), SourceFormatter.expression(node.toString()));
    }

    /**
     * An expression kept as its author wrote it — a part the grammar could not read, handed back untouched.
     * It needs no import: the file it came from already has them.
     */
    public static JavaValue kept(SourceNode written) {
        return new JavaValue(written.node(), List.of(), written.source());
    }

    /**
     * Source that is already some file's text, as a value to write back — the Java a row was snapshotted
     * with. Empty unless it is exactly one expression.
     */
    public static Optional<JavaValue> parse(String source) {
        return SourceNode.parse(source).map(JavaValue::kept);
    }

    /** {@link #node()} as a node of {@code ast}: what a rewrite of that tree may be handed. */
    public Expression copyInto(AST ast) {
        return (Expression) ASTNode.copySubtree(ast, node);
    }

    /** {@link #node()} as the grammar reads it, over the text it is shown as. */
    public SourceNode written() {
        return new SourceNode(node, null);
    }

    /**
     * Whether {@code other} writes the same Java, compared as trees — two values the grammar spells alike are
     * the same value, which a plugin's own {@code equals} cannot be trusted to say.
     */
    public boolean sameJava(JavaValue other) {
        return other != null && node.subtreeMatch(new ASTMatcher(), other.node);
    }
}
