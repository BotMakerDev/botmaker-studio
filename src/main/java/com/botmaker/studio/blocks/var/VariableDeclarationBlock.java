package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.blocks.expr.ListBlock;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.ExpressionSlots;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.vars.DeleteVariableDialog;
import com.botmaker.studio.ui.app.vars.EditVariableDialog;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.*;

import com.botmaker.studio.ui.render.components.TypeChip;

public class VariableDeclarationBlock extends AbstractStatementBlock {

    private final String variableName;
    private final ResolvedType varType;
    private ExpressionBlock initializer;

    public VariableDeclarationBlock(String id, VariableDeclarationStatement astNode) {
        super(id, astNode);
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) astNode.fragments().getFirst();
        this.variableName = fragment.getName().getIdentifier();
        this.varType = ProjectAnalyzer.resolveType(astNode.getType());
        this.initializer = null;
    }

    public void setInitializer(ExpressionBlock initializer) { this.initializer = initializer; }

    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    /**
     * {@code Rect area = ⟨value⟩ ⊕ ✎} — the declaration's sentence, declared.
     *
     * <p>Name and type are <em>shown</em> here and changed elsewhere, which is why neither is a slot. Both
     * used to be editable in place, and the inline rename went through {@code replaceSimpleName} on the
     * declaration alone — every use site kept the old name, so renaming a variable on its own block is how a
     * file stops compiling. The Variables screen rewrites the uses with it ({@code renameLocalVariable}) and
     * is the {@code ✎} at the end.
     *
     * <p>The starting value is the one {@code EXPRESSION_SLOT}, and it is the reason this block was worth
     * declaring before the bigger ones: a variable declaration is the commonest statement in a bot, and until
     * now the overlay HUD drew it as a line of source text with nothing to click.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("type", () -> TypeChip.of(((VariableDeclarationStatement) astNode).getType()))
                .custom("name", () -> TextFieldComponents.createVariableName(variableName, false, newName -> {}))
                .label("eq", () -> SentenceLayoutBuilder.keywordNode("="))
                .slot("value", () -> initializerNode(context))
                .picker("add", () -> createAddButton(e ->
                        showInitializerMenu((Button) e.getSource(), context, varType)))
                .picker("edit", () -> variablesButton(context))
                .build();
    }

    /**
     * Whatever stands for the starting value: a list, an array, a type-matched picker, or the dashed hole.
     *
     * <p>Was inline in {@code createUINode}; it is a method now because a spec declares one node per
     * component and this is the component that has four shapes.
     */
    private Node initializerNode(CodeEditorService context) {
        if (initializer == null) return emptyInitializer(context, varType);

        if (initializer instanceof ListBlock) return initializer.getUINode(context);
        if (initializer.getAstNode() instanceof ArrayInitializer) return createListDisplay(context);

        // Route the initializer through the same specialized pickers used for call arguments, keyed on
        // the declared variable type — so `ImageTemplate t = new ImageTemplate(...)`, `Rect r = ...`,
        // `Point p = ...`, `Direction d = ...` get their thumbnail/region/enum editor instead of a raw
        // expression node. Falls back to the generic node when no picker matches.
        Node picker = PickerRegistry.pickerNodeFor(PickerContext.of(context, ValueSlot.of(initializer), varType));
        Node initNode = picker != null ? picker : initializer.getUINode(context);
        // Dropping a call onto the value of a declaration is the same gesture as dropping it into any
        // other slot. The list/array renderings above stay out of it: they hold several expressions,
        // and a drop names exactly one to replace.
        ExpressionSlots.makeDroppable(initNode, initializer, context, varType);
        return initNode;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withCustomNode(renderSpec(context))
                .withDeleteButton(deleteAction(context))
                .build();
    }

    /**
     * The dashed hole where the starting value goes, opening the same menu as the ⊕ beside it.
     *
     * <p>{@code createEmptySlot} is deliberately not reused here: this block's write is
     * {@code setVariableInitializer}, which unwraps a collection type before asking what the value should be,
     * and a {@code List<Point>} initialised as if it were a {@code Point} is the bug that buys.
     */
    private Node emptyInitializer(CodeEditorService context, ResolvedType varType) {
        Node zone = createExpressionDropZone(context);
        if (isReadOnly()) return zone;
        zone.setCursor(javafx.scene.Cursor.HAND);
        zone.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            showInitializerMenu(zone, context, varType);
            e.consume();
        });
        return zone;
    }

    /** The starting-value menu, on the ⊕ or on the empty slot itself. */
    private void showInitializerMenu(Node anchor, CodeEditorService context, ResolvedType varType) {
        Expression current = initializer != null ? (Expression) initializer.getAstNode() : null;
        ContextMenu menu = ExpressionMenu.create(
                varType, false, context, this.astNode, x -> true,
                selection -> {
                    if (current != null) applyExpressionSelection(context, current, selection);
                    else context.getCodeEditor()
                            .setVariableInitializer((VariableDeclarationStatement) this.astNode, selection);
                });
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /**
     * The ✕ asks what becomes of the uses before it removes anything.
     *
     * <p>{@code deleteStatement} — what every other block's cross does, and what this one did — removes the line
     * and leaves each {@code attempts} in the method pointing at a name that no longer exists. A declaration is
     * the one statement whose deletion can break code somewhere else, so it is the one that needs a question.
     * {@link DeleteVariableDialog} asks it only when there are uses; an unused variable still goes in one press.
     */
    @Override
    protected Runnable deleteAction(CodeEditorService context) {
        if (isReadOnly()) return null;
        return () -> DeleteVariableDialog.confirmAndDelete(context, windowOf(context),
                (VariableDeclarationStatement) this.astNode);
    }

    /**
     * The way to this variable's screen from the block that declares it — a visible control, not a right-click
     * menu item, because it is now the <em>only</em> place this variable's name and type can be changed.
     *
     * <p>The pencil alone, with the words in the tooltip. It sits inside a sentence — {@code Rect area = …} —
     * where a second labelled button competes with the statement for the reader's eye, and the glyph is the
     * part that says what it does. Larger than the label it replaced for the same reason: it has to be a
     * target now rather than a caption.
     */
    private Node variablesButton(CodeEditorService context) {
        if (isReadOnly()) return null;
        Button open = new Button("✎");
        open.getStyleClass().add("variables-open-button");
        open.setTooltip(new Tooltip(
                "Rename or retype \"" + variableName + "\" — renaming here carries every use with it."));
        open.setOnAction(e -> EditVariableDialog.show(context, windowOf(context), variableName));
        return open;
    }

    private javafx.stage.Window windowOf(CodeEditorService context) {
        Node node = getUINode(context);
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "{", "}", false);
    }

    /** The same screen from the right-click menu, for anyone who looks for it there rather than on the block. */
    @Override
    public java.util.List<javafx.scene.control.MenuItem> blockMenuItems(CodeEditorService context) {
        javafx.scene.control.MenuItem item =
                new javafx.scene.control.MenuItem("Edit \"" + variableName + "\"…");
        item.setOnAction(e -> EditVariableDialog.show(context, windowOf(context), variableName));
        return java.util.List.of(item);
    }
}
