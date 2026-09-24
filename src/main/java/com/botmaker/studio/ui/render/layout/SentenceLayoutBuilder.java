package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.SelectorComponents;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SentenceLayoutBuilder {
    private final List<Node> nodes = new ArrayList<>();
    private double spacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;

    public SentenceLayoutBuilder addKeyword(String text) {
        nodes.add(keywordNode(text));
        return this;
    }

    public SentenceLayoutBuilder addLabel(String text) {
        nodes.add(labelNode(text));
        return this;
    }

    // The three factories below exist so a block that declares a ComponentSpec draws the *same* node as a block
    // that assembles a sentence here — a spec supplies one node at a time, and reimplementing "what a keyword
    // looks like" beside this class is how the two densities would start to disagree. The add* methods above
    // are now their only other callers.

    /** A keyword as the sentence layout builds one: {@code keyword-label}, clipped rather than ellipsized. */
    public static Label keywordNode(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("keyword-label");
        BlockComponent.Kind.LABEL.stamp(label);
        return noEllipsis(label);
    }

    /** A connecting word as the sentence layout builds one. */
    public static Label labelNode(String text) {
        Label label = new Label(text);
        BlockComponent.Kind.LABEL.stamp(label);
        return noEllipsis(label);
    }

    /**
     * The wrapping row ({@link WrappingSentencePane}) only ever clamps a token that is wider than a whole
     * line, so a label should clip rather than ellipsize when that happens — an "…" would be the very
     * "content hidden" symptom the wrapping layout exists to remove.
     */
    private static Label noEllipsis(Label label) {
        label.setTextOverrun(OverrunStyle.CLIP);
        return label;
    }

    /**
     * Adds {@code node}, or nothing at all when it is null.
     *
     * <p>Null is the "this affordance does not exist" signal, not an error: a read-only block's factories
     * ({@code AbstractStatementBlock.createAddButton}, {@code createDeleteButton},
     * {@code AbstractCodeBlock.createChangeButton}) return null, and the control is then simply never built.
     * A locked block must offer no interaction — not a disabled or greyed one — so there is nothing to click,
     * nothing to explain, and nothing for a future block to forget to guard.
     */
    public SentenceLayoutBuilder addNode(Node node) {
        if (node != null) nodes.add(node);
        return this;
    }

    /**
     * ResolvedType overload for addExpressionSlot
     */
    public SentenceLayoutBuilder addExpressionSlot(com.botmaker.studio.core.ExpressionBlock expression,
                                                   com.botmaker.studio.services.CodeEditorService context,
                                                   com.botmaker.studio.types.ResolvedType expectedType) {
        nodes.add(expressionSlotNode(expression, context, expectedType));
        return this;
    }

    /**
     * One expression slot, drop wiring and all — the node {@link #addExpressionSlot} adds.
     *
     * <p>A null {@code expression} is an empty slot and renders the placeholder, which is what a block whose
     * value was dragged out shows.
     */
    public static Node expressionSlotNode(com.botmaker.studio.core.ExpressionBlock expression,
                                          CodeEditorService context,
                                          com.botmaker.studio.types.ResolvedType expectedType) {
        if (expression == null) {
            Label placeholder = new Label("⟨expression⟩");
            placeholder.getStyleClass().add("block-placeholder");
            BlockComponent.Kind.EXPRESSION_SLOT.stamp(placeholder);
            return placeholder;
        }
        // A typed slot gets its specialized picker (image/group/rect/point/enum), same as call-argument
        // slots — so e.g. the whileFind/ifFind image slot is fillable, not just a raw expression node.
        Node picker = com.botmaker.studio.ui.render.components.pickers.PickerRegistry.pickerNodeFor(
                com.botmaker.studio.ui.render.components.pickers.PickerContext.of(context, com.botmaker.studio.core.ValueSlot.of(expression), expectedType));
        Node slotNode = picker != null ? picker : expression.getUINode(context);
        makeDroppable(slotNode, expression, context, expectedType);
        (picker != null ? BlockComponent.Kind.PICKER : BlockComponent.Kind.EXPRESSION_SLOT).stamp(slotNode);
        return slotNode;
    }

    public SentenceLayoutBuilder addOperatorSelector(
            String[] names,
            String[] symbols,
            String current,
            Consumer<String> onChange) {
        ComboBox<String> selector = SelectorComponents.createOperatorSelector(
                names, symbols, current, onChange
        );
        nodes.add(selector);
        return this;
    }

    public SentenceLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    // --- ADDED MISSING METHOD ---
    public SentenceLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    public HBox build() {
        // A wrapping row (not a plain HBox): overflowing pills fall onto indented continuation lines instead
        // of being squeezed/ellipsized. Returned as HBox so every caller (styleContainer, getChildren, CSS)
        // is unchanged — only the layout math differs.
        WrappingSentencePane container = new WrappingSentencePane(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(nodes);
        return container;
    }

    /**
     * Lets an expression slot receive a dropped block. This is the single place every slot in the editor is
     * built — {@code if}/{@code while} conditions, call arguments, print's argument — so wiring it here is what
     * makes "drag it into the slot" mean the same thing everywhere instead of per-block.
     *
     * <p>Read-only slots are left alone: a locked block offers no interaction at all, the same rule
     * {@code AbstractCodeBlock.createChangeButton} follows by returning null.
     */
    private static void makeDroppable(Node slotNode, com.botmaker.studio.core.ExpressionBlock expression,
                                      CodeEditorService context,
                                      com.botmaker.studio.types.ResolvedType expectedType) {
        ExpressionSlots.makeDroppable(slotNode, expression, context, expectedType);
    }
}
