package com.botmaker.studio.parser;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What stands as a line of a {@link Block}, in source order: its statements and the comments between them. An
 * insert index counts these lines, which is not the {@code Block.statements()} index, since a comment holds no
 * slot there. The converter draws one child per line and {@code CodeEditor} reads an index back through here,
 * so the two count alike.
 */
public final class BodyChildren {

    private BodyChildren() {}

    /** The comments in {@code comments} that stand as lines of {@code body}, not inside one of its statements. */
    public static List<Comment> commentsOf(Block body, List<Comment> comments) {
        int start = body.getStartPosition() + 1;
        int end = body.getStartPosition() + body.getLength() - 1;
        List<Comment> own = new ArrayList<>();
        for (Comment comment : comments) {
            int at = comment.getStartPosition();
            if (at > start && at < end && !insideAStatement(body, at)) own.add(comment);
        }
        return own;
    }

    private static boolean insideAStatement(Block body, int at) {
        for (Object statement : body.statements()) {
            ASTNode node = (ASTNode) statement;
            if (at >= node.getStartPosition() && at <= node.getStartPosition() + node.getLength()) return true;
        }
        return false;
    }

    /** The {@code statements()} index of the line at {@code childIndex}. */
    public static int statementIndex(Block body, int childIndex) {
        int index = 0;
        List<ASTNode> lines = lines(body);
        for (int i = 0; i < childIndex && i < lines.size(); i++) {
            if (!(lines.get(i) instanceof Comment)) index++;
        }
        return index;
    }

    /** The comment on the line just before {@code childIndex}, or {@code null} when that line is no comment. */
    public static Comment commentBefore(Block body, int childIndex) {
        List<ASTNode> lines = lines(body);
        if (childIndex <= 0 || childIndex > lines.size()) return null;
        return lines.get(childIndex - 1) instanceof Comment comment ? comment : null;
    }

    private static List<ASTNode> lines(Block body) {
        List<ASTNode> lines = new ArrayList<>();
        for (Object statement : body.statements()) lines.add((ASTNode) statement);
        if (body.getRoot() instanceof CompilationUnit unit) lines.addAll(commentsOf(body, nonJavadoc(unit)));
        lines.sort(Comparator.comparingInt(ASTNode::getStartPosition));
        return lines;
    }

    /** {@code unit}'s comments that can be a line of a body: a Javadoc never is. */
    public static List<Comment> nonJavadoc(CompilationUnit unit) {
        List<Comment> comments = new ArrayList<>();
        for (Object each : unit.getCommentList()) {
            if (each instanceof Comment comment && !(each instanceof Javadoc)) comments.add(comment);
        }
        return comments;
    }
}
