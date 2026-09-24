package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;

/**
 * Block for true/false values with toggle switch style UI.
 * Directly toggles on click.
 */
public class BooleanLiteralBlock extends AbstractExpressionBlock {

    private boolean value;

    public BooleanLiteralBlock(String id, BooleanLiteral astNode) {
        super(id, astNode);
        this.value = astNode.booleanValue();
    }

    /** No outline: like a typed-in number, a true/false toggle is the slot's value, and it draws its own chip. */
    @Override
    protected com.botmaker.studio.core.render.BlockShape shape() {
        return com.botmaker.studio.core.render.BlockShape.NONE;
    }

    public boolean getValue() {
        return value;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        StackPane root = new StackPane();
        root.getStyleClass().add("boolean-literal-block");

        // Visible pill label
        Label displayLabel = new Label(value ? "TRUE" : "FALSE");

        // Apply initial styles
        updateLabelStyle(displayLabel, value);

        StackPane.setAlignment(displayLabel, Pos.CENTER);

        // Handle toggle on click
        root.setOnMouseClicked(e -> {
            boolean newValue = !value;
            this.value = newValue;

            // Immediate UI feedback
            displayLabel.setText(newValue ? "TRUE" : "FALSE");
            updateLabelStyle(displayLabel, newValue);

            // Update AST
            context.getCodeEditor().replaceLiteralValue(
                    (Expression) this.astNode,
                    String.valueOf(newValue)
            );
        });

        root.getChildren().add(displayLabel);
        root.setMinWidth(60);
        root.setMaxHeight(24);

        // Show hand cursor to indicate interactivity
        root.setCursor(javafx.scene.Cursor.HAND);

        return root;
    }

    /**
     * The chip's look is {@code .boolean-chip} in {@code blocks.css}, from theme tokens. It was an inline
     * style of two hex literals with white text — which beat the stylesheet in every theme and scored 2.1:1
     * on the green (BlockStyleContrastTest).
     */
    private void updateLabelStyle(Label label, boolean val) {
        label.getStyleClass().removeAll("boolean-chip", "boolean-chip--true", "boolean-chip--false");
        label.getStyleClass().addAll("boolean-chip", val ? "boolean-chip--true" : "boolean-chip--false");
    }

    @Override
    public String getDetails() {
        return "Boolean: " + value;
    }
}
