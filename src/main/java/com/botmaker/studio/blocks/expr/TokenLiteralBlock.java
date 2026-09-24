package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.render.BlockShape;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.FieldSizing;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.TextBlock;

/**
 * A literal kept as the author spelled it: a number in hex, with underscores or an exponent ({@code 0xFF},
 * {@code 1_000}, {@code 2.5e3}), a character ({@code 'a'}), a text block ({@code """…"""}).
 *
 * <p>{@link LiteralBlock} writes a number back from the value it holds, so it takes only plain decimals — it
 * would print {@code 0xFF} as {@code 255}. Here the field holds the spelling and a commit respells the literal
 * ({@code CodeEditor.setNumberToken}), refused with a status line when the text is not one number. A character
 * is one character; a text block is a multi-line field whose lines keep the block's indentation when written.
 */
public class TokenLiteralBlock extends AbstractExpressionBlock {

    public TokenLiteralBlock(String id, Expression astNode) {
        super(id, astNode);
        if (!(astNode instanceof NumberLiteral) && !(astNode instanceof CharacterLiteral)
                && !(astNode instanceof TextBlock)) {
            throw new IllegalArgumentException("not a spelled literal: " + astNode);
        }
    }

    /** No outline, as {@link LiteralBlock}: a typed-in value is the slot's own well. */
    @Override
    protected BlockShape shape() {
        return BlockShape.NONE;
    }

    /** What the field shows: the number's spelling, the character, or the text block's content. */
    public String text() {
        return switch (astNode) {
            case NumberLiteral number -> number.getToken();
            case CharacterLiteral character -> String.valueOf(character.charValue());
            case TextBlock block -> block.getLiteralValue();
            default -> "";
        };
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        HBox container = new HBox(2);
        container.setAlignment(Pos.CENTER_LEFT);
        if (astNode instanceof TextBlock block) {
            container.getChildren().add(textBlockArea(block, context));
            return container;
        }
        boolean character = astNode instanceof CharacterLiteral;
        TextField field = new TextField(text());
        FieldSizing.fitToText(field);
        if (isReadOnly()) {
            field.setEditable(false);
            field.getStyleClass().addAll("block-inset-field", "block-inset-field--flat");
            field.setCursor(Cursor.DEFAULT);
        } else {
            field.focusedProperty().addListener((obs, was, focused) -> {
                if (focused) return;
                if (astNode instanceof CharacterLiteral literal) {
                    context.getCodeEditor().setCharacter(literal, field.getText());
                } else {
                    context.getCodeEditor().setNumberToken((NumberLiteral) astNode, field.getText());
                }
            });
        }
        Tooltip.install(field, new Tooltip(character
                ? "One character, written between single quotes."
                : "A number, kept as written (hex, underscores and exponents stay as they are)."));
        if (character) container.getChildren().add(SentenceLayoutBuilder.labelNode("'"));
        container.getChildren().add(field);
        if (character) container.getChildren().add(SentenceLayoutBuilder.labelNode("'"));
        return container;
    }

    private Node textBlockArea(TextBlock block, CodeEditorService context) {
        TextArea area = new TextArea(block.getLiteralValue());
        area.setPrefRowCount(Math.max(2, (int) block.getLiteralValue().lines().count()));
        area.setPrefColumnCount(24);
        area.setEditable(!isReadOnly());
        if (!isReadOnly()) {
            area.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) context.getCodeEditor().setTextBlock(block, area.getText());
            });
        }
        Tooltip.install(area, new Tooltip("A text block: several lines of text, kept as written."));
        return area;
    }
}
