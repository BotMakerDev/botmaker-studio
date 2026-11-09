package com.botmaker.studio.services;

import com.botmaker.core.edit.EditResult;
import com.botmaker.core.services.BotSources;
import com.botmaker.java.edit.EditContext;
import com.botmaker.java.grammar.SourceParser;
import com.botmaker.java.edit.refactor.ReviewMarks;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.parse.BotParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

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
     * Puts {@code item}'s function back as the newest version holds it <em>without</em> this mark — the state
     * just before the refactor that wrote it — and nothing else in the file. The core reads the versions and writes
     * it ({@code FunctionRestore}, asked by address through {@code edits}): one step of its history, its new text
     * drawn from what the core tells. Refused, with a sentence the caller shows, when it cannot. Blocking.
     */
    public static EditResult undo(EditPort edits, ProjectConfig config, Item item) {
        if (config == null || item == null) return new EditResult.Refused("Nothing to undo.");
        String file = config.sourceRoot().toAbsolutePath().normalize()
                .relativize(item.file().toAbsolutePath().normalize()).toString().replace('\\', '/');
        return edits.restore(file, item.function(), item.entries());
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

            EditContext ctx = EditContext.of(unit, null, PluginHost.typesOnly());
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
