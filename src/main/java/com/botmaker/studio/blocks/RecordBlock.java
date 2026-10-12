package com.botmaker.studio.blocks;

import org.eclipse.jdt.core.dom.RecordDeclaration;

/**
 * A record: {@code Record: Spot(int x, int y) implements Point}, its components in the header, then its own
 * members — methods, static fields, nested types — each drawn and edited as a class's are. Nothing is added from
 * here: the class toolbar's functions and enums are a class's, and a record's components are changed in the code.
 */
public class RecordBlock extends TypeBlock {

    public RecordBlock(String id, RecordDeclaration astNode) {
        super(id, astNode);
    }

    /** A solid edge in the Variables colour ({@code blocks.css}). */
    @Override
    protected String styleClass() {
        return "record-block";
    }
}
