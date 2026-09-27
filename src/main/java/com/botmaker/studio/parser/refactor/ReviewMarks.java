package com.botmaker.studio.parser.refactor;

import com.botmaker.plugin.api.meta.Refactor;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.project.source.BotAnnotation;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The record a refactor leaves where it guessed: the contract's {@code @Refactor} on the function it rewrote.
 *
 * <h2>Why the source, and not a sidecar</h2>
 *
 * <p>A refactor that guesses — an upgrade standing a default value in for a call the new jar no longer
 * offers, a signature change filling a new parameter at every call site — compiles and is very often wrong
 * (see {@link ApiMigrationRunner}). Something has to survive the dialog closing and tell the user which
 * functions those were, and the diff cannot. So the mark goes where the change went: an edit that moves the
 * function moves the mark, deleting it deletes the mark, and a revert through Versions takes the marks back
 * out with the change.
 *
 * <h2>The contract's annotation, found by class (2026-09-27)</h2>
 *
 * <p>Until then Studio generated a {@code NeedsReview} annotation into the bot's own package the first time it
 * marked anything and matched it by simple name. It is now {@link Refactor}, in the contract every plugin bot
 * already compiles against, read through {@link BotAnnotation#REFACTOR}. A marker only writes it where the
 * classpath carries it ({@link ReviewMarker#available}); an old {@code NeedsReview} is left alone.
 *
 * <h2>Merging, and reviewing without deleting</h2>
 *
 * <p>Marks accumulate — two upgrades and a signature change can all land on one function — so writing is a
 * merge into the annotation already there, and a new guess reopens a reviewed one. Reviewing sets
 * {@code done = true} and keeps the entries: the record of what happened stays with the code until the user
 * deletes the annotation.
 */
public final class ReviewMarks {

    private ReviewMarks() {}

    /** The annotation's simple name — the cheap text test before a file is parsed. */
    public static final String ANNOTATION = Refactor.class.getSimpleName();

    /** What a mark reads as: its entries and whether it was reviewed. */
    public record Mark(List<String> entries, boolean done) {}

    // --- reading ----------------------------------------------------------------------------------------------

    /** The nearest enclosing function of {@code node}, or null when it is not inside one. */
    public static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /** {@code method}'s mark, or null when it carries none. */
    public static Mark markOn(MethodDeclaration method) {
        Annotation annotation = annotationOn(method);
        return annotation == null ? null : read(annotation);
    }

    /** {@code method}'s entries still waiting for review, empty when unmarked or reviewed. */
    public static List<String> openEntriesOf(MethodDeclaration method) {
        Mark mark = markOn(method);
        return mark == null || mark.done() ? List.of() : mark.entries();
    }

    /** Every marked function in {@code unit}, reviewed or not, in source order. */
    public static List<MethodDeclaration> markedIn(CompilationUnit unit) {
        List<MethodDeclaration> marked = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (annotationOn(node) != null) marked.add(node);
                return true;
            }
        });
        return marked;
    }

    // --- writing ----------------------------------------------------------------------------------------------

    /**
     * Records {@code entries} on {@code method}, merging with a mark already there — and reopening it, since a
     * new guess has not been looked at — and importing the annotation.
     *
     * <p>The edit goes into {@code ctx}'s rewrite, so the mark lands with the change it describes or not at
     * all. Whether to call this at all is the caller's: {@link ReviewMarker#available}.
     */
    public static void mark(EditContext ctx, MethodDeclaration method, List<String> entries) {
        if (method == null || entries == null || entries.isEmpty()) return;

        Annotation existing = annotationOn(method);
        Set<String> merged = new LinkedHashSet<>(existing == null ? List.of() : read(existing).entries());
        merged.addAll(entries);

        write(ctx, method, existing, new Mark(List.copyOf(merged), false));
        ctx.addImport(Refactor.class);
    }

    /**
     * Sets {@code done = true} on {@code method}'s mark — the "I have looked at this" gesture, which keeps the
     * record.
     *
     * @return false when there is no open mark, so the caller can leave the file alone
     */
    public static boolean markReviewed(EditContext ctx, MethodDeclaration method) {
        Annotation existing = annotationOn(method);
        if (existing == null) return false;
        Mark mark = read(existing);
        if (mark.done()) return false;
        write(ctx, method, existing, new Mark(mark.entries(), true));
        return true;
    }

    /** Removes {@code method}'s whole mark, and the import once nothing else in the file carries one. */
    public static boolean remove(EditContext ctx, MethodDeclaration method) {
        Annotation existing = annotationOn(method);
        if (existing == null) return false;
        modifiers(ctx.rewriter(), method).remove(existing, null);
        removeImportIfLast(ctx, method);
        return true;
    }

    // --- the shape of the annotation --------------------------------------------------------------------------

    private static Annotation annotationOn(MethodDeclaration method) {
        return method == null ? null : BotAnnotation.REFACTOR.on(method);
    }

    /** Every form Java allows it to be written in reads back: the file is the user's. */
    private static Mark read(Annotation annotation) {
        Map<String, Object> members = BotAnnotation.REFACTOR.members(annotation);
        List<String> entries = switch (members.get("value")) {
            case List<?> list -> list.stream().map(String::valueOf).toList();
            case String one -> List.of(one);
            case null, default -> List.of();
        };
        return new Mark(entries, Boolean.TRUE.equals(members.get("done")));
    }

    private static void write(EditContext ctx, MethodDeclaration method, Annotation existing, Mark mark) {
        Annotation replacement = annotationFor(ctx.ast(), mark);
        if (existing != null) {
            ctx.rewriter().replace(existing, replacement, null);
        } else {
            modifiers(ctx.rewriter(), method).insertFirst(replacement, null);
        }
    }

    /**
     * A fresh {@code @Refactor} — {@code ("…")} or {@code ({"…", "…"})} while open, the named form once it
     * says {@code done = true} — which is how a person would have written it.
     */
    @SuppressWarnings("unchecked")
    private static Annotation annotationFor(AST ast, Mark mark) {
        Expression value;
        if (mark.entries().size() == 1) {
            value = literal(ast, mark.entries().getFirst());
        } else {
            ArrayInitializer array = ast.newArrayInitializer();
            for (String entry : mark.entries()) array.expressions().add(literal(ast, entry));
            value = array;
        }
        if (!mark.done()) {
            SingleMemberAnnotation annotation = ast.newSingleMemberAnnotation();
            annotation.setTypeName(ast.newSimpleName(ANNOTATION));
            annotation.setValue(value);
            return annotation;
        }
        NormalAnnotation annotation = ast.newNormalAnnotation();
        annotation.setTypeName(ast.newSimpleName(ANNOTATION));
        annotation.values().add(pair(ast, "value", value));
        annotation.values().add(pair(ast, "done", ast.newBooleanLiteral(true)));
        return annotation;
    }

    private static MemberValuePair pair(AST ast, String name, Expression value) {
        MemberValuePair pair = ast.newMemberValuePair();
        pair.setName(ast.newSimpleName(name));
        pair.setValue(value);
        return pair;
    }

    private static StringLiteral literal(AST ast, String text) {
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(text);
        return literal;
    }

    private static ListRewrite modifiers(ASTRewrite rewriter, MethodDeclaration method) {
        return rewriter.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
    }

    /**
     * Drops the import once {@code method} was the file's last marked function — asked of the <em>original</em>
     * tree, which still shows the annotation this same rewrite is removing.
     */
    private static void removeImportIfLast(EditContext ctx, MethodDeclaration method) {
        List<MethodDeclaration> marked = markedIn(ctx.cu());
        if (marked.size() != 1 || marked.getFirst() != method) return;

        for (Object each : ctx.cu().imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (!imported.isOnDemand() && !imported.isStatic()
                    && Refactor.class.getName().equals(imported.getName().getFullyQualifiedName())) {
                ctx.rewriter().remove(imported, null);
            }
        }
    }
}
