package com.botmaker.studio.parser.handlers;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchStatement;

/**
 * Replaces one statement with text the user typed — the edit behind {@code SourceStatementBlock}, the block a
 * statement gets when no other block draws it.
 *
 * <p>The text is spliced in as typed, not rebuilt from a tree: the user wrote it, and a re-print would move
 * their spacing and comments. What is checked is only that it <em>is</em> Java statements. A typo is refused,
 * not written, because a file that no longer parses draws as an empty canvas — the one outcome worse than the
 * statement staying as it was.
 */
public final class StatementSourceHandler {

    private StatementSourceHandler() {}

    /**
     * {@code originalCode} with {@code target} replaced by {@code newSource}, or {@code null} when the text is
     * blank, does not parse as statements, or is several statements where only one can stand (the body of a
     * labelled statement, say — only a block or a switch holds a list).
     */
    public static String replace(String originalCode, Statement target, String newSource) {
        if (target == null || newSource == null || newSource.isBlank()) return null;
        int count = statementCount(newSource);
        if (count == 0) return null;
        boolean inList = target.getParent() instanceof Block || target.getParent() instanceof SwitchStatement;
        if (count > 1 && !inList) return null;

        int start = target.getStartPosition();
        int end = start + target.getLength();
        return originalCode.substring(0, start) + newSource.strip() + originalCode.substring(end);
    }

    /** How many statements {@code source} holds, or 0 when it is not well-formed Java statements. */
    static int statementCount(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_STATEMENTS);
        parser.setSource(source.toCharArray());
        ASTNode parsed = parser.createAST(null);
        if (!(parsed instanceof Block block)) return 0;
        boolean[] damaged = {false};
        block.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if ((node.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) damaged[0] = true;
            }
        });
        return damaged[0] ? 0 : block.statements().size();
    }
}
