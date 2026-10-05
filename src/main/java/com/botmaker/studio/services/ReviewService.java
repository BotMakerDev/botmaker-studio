package com.botmaker.studio.services;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionReader;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * What every refactor left for the user to look at, read back out of the bot's own source.
 *
 * <h2>Why a scan, and not a list something kept</h2>
 *
 * <p>The marks are written into the code ({@link ReviewMarks}) precisely so that nothing has to keep a list:
 * an edit that moves a function moves its mark, deleting the function deletes it, and a revert through
 * Versions takes the marks out with the change they describe. The price of that is that the list has to be
 * <em>derived</em> — every reader of it re-reads the sources. That is cheap (a bot is tens of files) and it
 * cannot go stale.
 *
 * <p>So there is exactly one rule here: <b>the source is the truth</b>. {@link #scan} lists each function
 * whose {@code @Refactor} is not {@code done}, and {@link #markReviewed} sets {@code done = true} through the
 * same {@link BotSources} walk the template rewrites use, so the open buffer and the file on disk never
 * disagree. A reviewed mark stays in the code as the record; the user deletes it when they no longer want it.
 */
public final class ReviewService {

    private ReviewService() {}

    /**
     * One function to look at, with everything its open mark says.
     *
     * <p>{@code line} is where the function starts — the unit the user reviews and the canvas scrolls to.
     */
    public record Item(Path file, String function, int line, List<String> entries) {

        /** {@code "Miner.java · mine()"} — the row's own heading. */
        public String where() {
            return file.getFileName() + " · " + function + "()";
        }
    }

    /** Every function with an open mark in the project, in file then source order. */
    public static List<Item> scan(ProjectConfig config, ProjectState state) {
        List<Item> items = new ArrayList<>();
        if (config == null) return items;
        BotSources.forEach(config, state, (file, source) -> {
            // Cheap reject before parsing: most files carry no mark at all, and parsing is the expensive half.
            if (!source.contains(ReviewMarks.ANNOTATION)) return null;
            CompilationUnit unit = BotParser.syntax(source);
            if (unit == null) return null;
            for (MethodDeclaration method : ReviewMarks.markedIn(unit)) {
                List<String> open = ReviewMarks.openEntriesOf(method);
                if (open.isEmpty()) continue;
                int line = unit.getLineNumber(method.getStartPosition());
                items.add(new Item(file, method.getName().getIdentifier(), Math.max(line, 1), open));
            }
            return null;   // reading only
        });
        return List.copyOf(items);
    }

    /**
     * Sets {@code done = true} on {@code item}'s mark — the "I have looked at this" gesture — and answers
     * whether anything changed.
     *
     * <p>A file that no longer holds the mark is not an error: the user may have edited it away by hand, or
     * reverted the change since the list was drawn. It answers false and the caller re-scans.
     */
    public static boolean markReviewed(ProjectConfig config, ProjectState state, Item item) {
        return rewriteMarked(config, state, item, ReviewMarks::markReviewed);
    }

    /**
     * Takes {@code item}'s mark out of the code altogether — the annotation, and its import once it was the
     * file's last — and answers whether anything changed. The record goes with it; a version still has it.
     */
    public static boolean removeMark(ProjectConfig config, ProjectState state, Item item) {
        return rewriteMarked(config, state, item, ReviewMarks::remove);
    }

    /**
     * Where an undo stands: the earlier function found ({@link #findEarlier}), the version it came back from
     * ({@link #restore}), or why it did not.
     */
    public sealed interface Undo {
        record Ready(Earlier earlier) implements Undo {}

        record Done(String version) implements Undo {}

        record Refused(String reason) implements Undo {}
    }

    /**
     * Puts {@code item}'s function back as the newest version holds it <em>without</em> this mark — the state
     * just before the refactor that wrote it, which every refactor saves first — and nothing else in the file.
     * Any version counts, not only the refactor's own: the newest one without the mark is the one before it.
     * Blocking (it reads the project's history) and on one thread: a window runs {@link #findEarlier} on a
     * worker and {@link #restore} on the FX thread.
     *
     * <p>It refuses, with a sentence, when no version has the function without the mark, or when what it would
     * write does not parse. What it brings back may not compile: the refactor ran because something it used
     * went away.
     */
    public static Undo undo(ProjectConfig config, ProjectState state, Item item) {
        Undo found = findEarlier(config, item, parametersOf(config, state, item));
        return found instanceof Undo.Ready ready ? restore(config, state, item, ready.earlier()) : found;
    }

    /**
     * {@code item}'s function as an earlier version wrote it — the version's title, the declaration, and the
     * single-type imports of that file the declaration names.
     */
    public record Earlier(String version, String declaration, List<String> imports) {}

    /**
     * The parameter types of {@code item}'s marked function as the file has it now — what tells it from an
     * overload of the same name. Read the editor's buffers, so on the FX thread. Empty when it is not found.
     */
    public static List<String> parametersOf(ProjectConfig config, ProjectState state, Item item) {
        if (config == null || item == null) return List.of();
        CompilationUnit unit = BotSources.sourceOf(config, state, item.file()).map(BotParser::syntax).orElse(null);
        if (unit == null) return List.of();
        MethodDeclaration target = markedTarget(unit, item);
        return target == null ? List.of() : parameterTypes(target);
    }

    /**
     * The newest version's {@code item} without the mark, matched by name and {@code parameters}: an
     * {@link Undo.Ready} holding it, or {@link Undo.Refused}. Reads the project's history and no editor state,
     * so it may run off the FX thread.
     */
    public static Undo findEarlier(ProjectConfig config, Item item, List<String> parameters) {
        if (config == null || item == null) return new Undo.Refused("Nothing to undo.");
        String relative = config.projectPath().toAbsolutePath().normalize()
                .relativize(item.file().toAbsolutePath().normalize()).toString().replace('\\', '/');
        try {
            VersionReader reader = new VersionReader(config.projectPath());
            for (ProjectVcs.CommitInfo commit : new ProjectVcs(config.projectPath()).history()) {
                byte[] bytes = reader.at(commit.sha(), relative);
                if (bytes == null) continue;
                Earlier before = unmarkedDeclaration(new String(bytes, StandardCharsets.UTF_8), item, parameters,
                        commit.title());
                if (before != null) return new Undo.Ready(before);
            }
        } catch (IOException e) {
            return new Undo.Refused("This bot's versions could not be read: " + e.getMessage());
        }
        return new Undo.Refused("No saved version has " + item.function() + "() as it was before this change.");
    }

    /**
     * Writes {@code earlier} in place of {@code item}'s function, with the imports it names. Through the editor's
     * buffers, so on the FX thread.
     */
    public static Undo restore(ProjectConfig config, ProjectState state, Item item, Earlier earlier) {
        boolean done = rewriteMarked(config, state, item, (ctx, method) -> {
            ctx.rewriter().replace(method,
                    ctx.rewriter().createStringPlaceholder(earlier.declaration(), method.getNodeType()), null);
            // The refactor dropped the imports only the old body used; the canvas's unused-import pass takes
            // back any the restored function does not need.
            for (String name : earlier.imports()) ctx.addImport(name);
            return true;
        });
        return done ? new Undo.Done(earlier.version())
                : new Undo.Refused(item.function() + "() changed since the list was drawn, or the old version of it"
                        + " does not fit the file as it is now.");
    }

    private static List<String> parameterTypes(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (Object each : method.parameters()) {
            var parameter = (org.eclipse.jdt.core.dom.SingleVariableDeclaration) each;
            types.add(parameter.getType().toString() + (parameter.isVarargs() ? "..." : ""));
        }
        return List.copyOf(types);
    }

    /** {@code item}'s function in {@code source} when it carries none of the item's entries, else null. */
    private static Earlier unmarkedDeclaration(String source, Item item, List<String> parameters, String version) {
        CompilationUnit unit = BotParser.syntax(source);
        if (unit == null) return null;
        for (Object type : unit.types()) {
            if (!(type instanceof org.eclipse.jdt.core.dom.TypeDeclaration declaration)) continue;
            for (MethodDeclaration method : declaration.getMethods()) {
                if (!method.getName().getIdentifier().equals(item.function())) continue;
                if (!parameterTypes(method).equals(parameters)) continue;
                if (ReviewMarks.openEntriesOf(method).stream().anyMatch(item.entries()::contains)) continue;
                String text = source.substring(method.getStartPosition(),
                        method.getStartPosition() + method.getLength());
                List<String> imports = new ArrayList<>();
                for (Object each : unit.imports()) {
                    var imported = (org.eclipse.jdt.core.dom.ImportDeclaration) each;
                    if (imported.isStatic() || imported.isOnDemand()) continue;
                    String qualified = imported.getName().getFullyQualifiedName();
                    String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
                    if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(simple) + "\\b")
                            .matcher(text).find()) {
                        imports.add(qualified);
                    }
                }
                return new Earlier(version, text, List.copyOf(imports));
            }
        }
        return null;
    }

    /**
     * One rewrite of {@code item}'s marked function through {@link BotSources}, so the open buffer and the
     * disk agree; {@code change} answers false to leave the file alone.
     */
    private static boolean rewriteMarked(ProjectConfig config, ProjectState state, Item item,
                                         BiPredicate<EditContext, MethodDeclaration> change) {
        if (config == null || item == null) return false;
        boolean[] changed = {false};
        BotSources.forEach(config, state, (file, source) -> {
            if (changed[0] || !file.equals(item.file())) return null;
            CompilationUnit unit = BotParser.syntax(source);
            if (unit == null || SourceParser.hasSyntaxErrors(unit)) return null;

            MethodDeclaration target = markedTarget(unit, item);
            if (target == null) return null;

            EditContext ctx = EditContext.of(unit, null, null);
            if (!change.test(ctx, target)) return null;
            String rewritten = ctx.applyTo(source);
            // Same rule as every other rewrite in Studio: a result that does not parse is not written.
            if (rewritten == null || SourceParser.hasSyntaxErrors(BotParser.syntax(rewritten))) return null;
            changed[0] = true;
            return rewritten;
        });
        return changed[0];
    }

    /** {@code item}'s function in {@code unit}, or null. */
    private static MethodDeclaration markedTarget(CompilationUnit unit, Item item) {
        for (MethodDeclaration method : ReviewMarks.markedIn(unit)) {
            // The entries, not the name alone: two overloads can both be marked.
            if (method.getName().getIdentifier().equals(item.function())
                    && ReviewMarks.openEntriesOf(method).equals(item.entries())) {
                return method;
            }
        }
        return null;
    }
}
