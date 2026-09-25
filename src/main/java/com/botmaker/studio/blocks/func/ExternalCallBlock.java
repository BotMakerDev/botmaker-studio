package com.botmaker.studio.blocks.func;

import org.eclipse.jdt.core.dom.ASTNode;

/**
 * "use ‹owner› ‹class› ‹method›": a call into code the bot does not own — a plugin's ({@code Mouse.click()}),
 * the JDK's ({@code Math.max}) or a library's. The owner is a small pill ({@link CallOwner}); the scope
 * dropdown lists each plugin's classes under the plugin's name, then the JDK's and the libraries' static
 * classes, and picking one rewrites the call onto it.
 *
 * <p>It replaced {@code LibraryCallBlock} on 2026-09-25, which drew a plugin's facade call in a pale frame of
 * its own ({@code .sdk-call-block}) and could only move between that plugin's facades. A plugin's call is a
 * FUNCTIONS block now, like every other call.
 */
public class ExternalCallBlock extends MethodInvocationBlock {

    /**
     * @param facade the plugin facade the call is on, whose docs and deprecations it reads; null for any other
     *               external call
     */
    public ExternalCallBlock(String id, ASTNode astNode, String facade) {
        super(id, astNode);
        if (facade != null) setFixedScope(facade);
    }

    @Override
    protected boolean external() {
        return true;
    }

    @Override
    protected String verb() {
        return "use";
    }

    @Override
    public String getDetails() {
        return "Use " + getScope() + "." + methodName + "()";
    }
}
