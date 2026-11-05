package com.botmaker.studio.nav;

import com.botmaker.studio.core.CodeBlock;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;

import java.util.Map;
import java.util.Optional;

/** From a place in the source to the block drawn for it: the drawing's half of {@code SourceNavigation}. */
public final class BlockAt {

    private BlockAt() {}

    /** The block drawn for what sits at {@code offset} in {@code cu}, or its nearest drawn ancestor. */
    public static Optional<CodeBlock> blockAtOffset(CompilationUnit cu, int offset, Map<ASTNode, CodeBlock> nodeToBlock) {
        if (cu == null || offset < 0) return Optional.empty();
        return blockFor(NodeFinder.perform(cu, offset, 0), nodeToBlock);
    }

    /** The block drawn for {@code node} or, failing that, for the nearest ancestor that has one. */
    public static Optional<CodeBlock> blockFor(ASTNode node, Map<ASTNode, CodeBlock> nodeToBlock) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            CodeBlock block = nodeToBlock.get(n);
            if (block != null) return Optional.of(block);
        }
        return Optional.empty();
    }
}
