package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

public class HeaderLayoutBuilder {
    private final List<Node> leftContent = new ArrayList<>();
    private final List<Node> rightContent = new ArrayList<>();
    private Runnable onDelete;
    private double spacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;
    /** The one node that should absorb the header's spare width, if any. See {@link #withGrowingNode}. */
    private Node growingNode;

    public HeaderLayoutBuilder withKeyword(String text) {
        leftContent.add(SentenceLayoutBuilder.keywordNode(text));
        return this;
    }

    public HeaderLayoutBuilder withLabel(String text) {
        leftContent.add(SentenceLayoutBuilder.labelNode(text));
        return this;
    }

    public HeaderLayoutBuilder withChangeButton(Runnable onClick) {
        Button btn = new Button("+");
        btn.getStyleClass().add("icon-button");
        btn.setOnAction(e -> onClick.run());
        leftContent.add(btn);
        return this;
    }

    public HeaderLayoutBuilder withAddButton(Runnable onClick) {
        Button btn = new Button("+");
        btn.getStyleClass().add("expression-add-button");
        btn.setOnAction(e -> onClick.run());
        leftContent.add(btn);
        return this;
    }

    public HeaderLayoutBuilder withDeleteButton(Runnable onDelete) {
        this.onDelete = onDelete;
        return this;
    }

    public HeaderLayoutBuilder withCustomNode(Node node) {
        if (node != null) leftContent.add(node);
        return this;
    }

    /**
     * Like {@link #withCustomNode}, but {@code node} absorbs the header's spare width instead of the spacer.
     *
     * <p>Use this whenever {@code node} contains something that should stretch — a text field, most obviously.
     * An {@code Hgrow} only distributes space its <em>direct</em> parent has: a growing field inside a sentence
     * HBox added as a plain custom node never grows, because {@link #build()}'s spacer takes all the slack and
     * the sentence is left at its preferred width. That is why a long comment scrolled inside a ~120px field —
     * the field asked to grow, and the row it was in had nothing to give it.
     */
    public HeaderLayoutBuilder withGrowingNode(Node node) {
        if (node != null) {
            leftContent.add(node);
            growingNode = node;
        }
        return this;
    }

    public HeaderLayoutBuilder withRightNode(Node node) {
        rightContent.add(node);
        return this;
    }

    public HeaderLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    public HeaderLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    // Terminal operation - builds and returns the Node
    public HBox build() {
        HBox container = new HBox(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(leftContent);

        if (onDelete != null || !rightContent.isEmpty()) {
            // A growing node already pushes the right-hand content over, and adding a spacer beside it would
            // split the spare width between the two — the field would only ever get half of it.
            if (growingNode != null) {
                HBox.setHgrow(growingNode, Priority.ALWAYS);
            } else {
                Pane spacer = new Pane();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                container.getChildren().add(spacer);
            }

            container.getChildren().addAll(rightContent);

            if (onDelete != null) {
                container.getChildren().add(
                        com.botmaker.studio.ui.render.components.BlockUIComponents.createDeleteButton(onDelete));
            }
        } else if (growingNode != null) {
            HBox.setHgrow(growingNode, Priority.ALWAYS);
        }

        return container;
    }

    // Chaining into body builder
    public BodyLayoutBuilder andBody() {
        HBox header = build();
        return new BodyLayoutBuilder(header);
    }

    // ===== HELPER METHODS =====

}
