package com.botmaker.studio.blocks.func;

import org.eclipse.jdt.core.dom.ASTNode;

/**
 * "call ‹method›": a call to one of the bot's own methods. Its scope dropdown lists the variables in reach and
 * the bot's own classes, and nothing a plugin or a jar declares — that is an {@link ExternalCallBlock}.
 */
public class ProjectCallBlock extends MethodInvocationBlock {

    public ProjectCallBlock(String id, ASTNode astNode) {
        super(id, astNode);
    }

    @Override
    protected boolean external() {
        return false;
    }

    @Override
    protected String verb() {
        return "call";
    }
}
