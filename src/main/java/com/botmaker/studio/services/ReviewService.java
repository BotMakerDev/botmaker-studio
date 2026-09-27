package com.botmaker.studio.services;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        if (config == null || item == null) return false;
        boolean[] reviewed = {false};
        BotSources.forEach(config, state, (file, source) -> {
            if (reviewed[0] || !file.equals(item.file())) return null;
            CompilationUnit unit = BotParser.syntax(source);
            if (unit == null || SourceParser.hasSyntaxErrors(unit)) return null;

            MethodDeclaration target = null;
            for (MethodDeclaration method : ReviewMarks.markedIn(unit)) {
                // The entries, not the name alone: two overloads can both be marked.
                if (method.getName().getIdentifier().equals(item.function())
                        && ReviewMarks.openEntriesOf(method).equals(item.entries())) {
                    target = method;
                    break;
                }
            }
            if (target == null) return null;

            EditContext ctx = EditContext.of(unit, null, null);
            if (!ReviewMarks.markReviewed(ctx, target)) return null;
            String rewritten = ctx.applyTo(source);
            // Same rule as every other rewrite in Studio: a result that does not parse is not written.
            if (rewritten == null || SourceParser.hasSyntaxErrors(BotParser.syntax(rewritten))) return null;
            reviewed[0] = true;
            return rewritten;
        });
        return reviewed[0];
    }
}
