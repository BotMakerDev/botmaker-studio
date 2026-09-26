package com.botmaker.studio.services.debug;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How fast Follow is allowed to move the highlight, and what it shows when the bot is faster than that.
 *
 * <p>The limits are for the person watching. The highlight moves at most once per {@link #DWELL_MS}: a quarter
 * of a second is slow enough to read and well under the three flashes a second photosensitivity guidance draws
 * the line at. When a block runs twice inside one window the bot is looping faster than anyone can follow, so
 * the frame says {@code looping} and carries every line the window saw; the view then holds the highlight
 * still on the loop that encloses them, instead of stepping through its body four times a second.
 *
 * <p>Fed from the JDI thread, taken by a timer; pure otherwise.
 */
public final class FollowPacer {

    public static final long DWELL_MS = 250;

    /** One stop: a line of a source file. */
    public record Hit(Path file, int line) {}

    /** What one window saw: the latest hit, every distinct hit, and whether any of them came round twice. */
    public record Frame(Hit latest, Set<Hit> hits, boolean looping) {
        /** The file every hit is in, or empty when the window crossed files. */
        public Optional<Path> singleFile() {
            return hits.stream().allMatch(h -> h.file().equals(latest.file()))
                    ? Optional.of(latest.file()) : Optional.empty();
        }
    }

    private final Set<Hit> window = new LinkedHashSet<>();
    private Hit latest;
    private boolean repeated;

    public synchronized void hit(Hit hit) {
        if (!window.add(hit)) repeated = true;
        latest = hit;
    }

    /** What the window since the last call saw, and a fresh window; null when the bot ran no line in it. */
    public synchronized Frame take() {
        if (latest == null) return null;
        Frame frame = new Frame(latest, Set.copyOf(window), repeated);
        window.clear();
        latest = null;
        repeated = false;
        return frame;
    }

    /**
     * The innermost loop statement that holds every one of {@code nodes}, itself included — the block Follow
     * rests on while the bot spins inside it. Empty when they share no loop (a recursion, or a loop in the
     * caller of this file).
     */
    public static Optional<ASTNode> enclosingLoop(List<ASTNode> nodes) {
        if (nodes.isEmpty()) return Optional.empty();
        for (ASTNode candidate = nodes.getFirst(); candidate != null; candidate = candidate.getParent()) {
            if (!isLoop(candidate)) continue;
            ASTNode loop = candidate;
            if (nodes.stream().allMatch(n -> isWithin(n, loop))) return Optional.of(loop);
        }
        return Optional.empty();
    }

    private static boolean isLoop(ASTNode node) {
        return node instanceof WhileStatement || node instanceof ForStatement
                || node instanceof EnhancedForStatement || node instanceof DoStatement;
    }

    private static boolean isWithin(ASTNode node, ASTNode ancestor) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n == ancestor) return true;
        }
        return false;
    }
}
