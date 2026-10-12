package com.botmaker.studio.blocks;

import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * An interface: {@code Interface: Step extends Runnable}, then its members — constants, abstract and default
 * methods — each drawn and edited as a class's are. Nothing is added from here: a function the class toolbar
 * writes is a static method with a body, not what an interface declares.
 */
public class InterfaceBlock extends TypeBlock {

    public InterfaceBlock(String id, TypeDeclaration astNode) {
        super(id, astNode);
        if (!astNode.isInterface()) throw new IllegalArgumentException(astNode.getName() + " is a class");
    }

    /** A dashed edge in the Functions colour ({@code blocks.css}). */
    @Override
    protected String styleClass() {
        return "interface-block";
    }
}
