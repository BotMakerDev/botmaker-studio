package com.botmaker.studio.blocks;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;

/**
 * A type block's header row — {@code Class: Box<T>}, {@code Record: Spot(int x, int y)} — then each inheritance
 * clause ({@link TypeClauses}) as its own de-emphasised run, so the type's own name reads first and what it extends
 * or implements is still there. One look for {@link ClassBlock} and {@link RecordBlock}.
 */
public final class TypeHeader {

    private TypeHeader() {}

    public static Node of(AbstractTypeDeclaration type) {
        Label name = new Label(TypeClauses.kind(type) + ": " + type.getName().getIdentifier()
                + TypeClauses.typeParameters(type) + TypeClauses.components(type));
        name.getStyleClass().add("class-block-title");

        HBox header = new HBox(8, name);
        header.setAlignment(Pos.BASELINE_LEFT);
        header.setPadding(new Insets(0, 0, 15, 0));
        for (String clause : TypeClauses.inheritance(type)) header.getChildren().add(clause(clause));
        return header;
    }

    /** One inheritance clause, {@code implements Outcome}, de-emphasised so the type's own name reads first. */
    public static Label clause(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("class-inheritance", "class-block-subtitle");
        return label;
    }

    /** {@code type}'s header as it reads in source: {@code Foo<T> extends Bar implements Baz}. */
    public static String text(AbstractTypeDeclaration type) {
        StringBuilder sb = new StringBuilder(type.getName().getIdentifier()).append(TypeClauses.typeParameters(type))
                .append(TypeClauses.components(type));
        for (String clause : TypeClauses.inheritance(type)) sb.append(' ').append(clause);
        return sb.toString();
    }
}
