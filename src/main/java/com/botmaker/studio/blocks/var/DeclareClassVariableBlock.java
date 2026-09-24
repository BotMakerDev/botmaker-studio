package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;

import com.botmaker.studio.blocks.expr.ListBlock;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import com.botmaker.studio.ui.render.components.TypeChip;

public class DeclareClassVariableBlock extends AbstractStatementBlock {

    private final String variableName;
    private final ResolvedType fieldType;
    private final boolean isStatic;
    private final boolean isPrivate;
    private ExpressionBlock initializer;

    public DeclareClassVariableBlock(String id, FieldDeclaration astNode) {
        super(id, astNode);
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) astNode.fragments().getFirst();
        this.variableName = fragment.getName().getIdentifier();
        this.fieldType = ProjectAnalyzer.resolveType(astNode.getType());
        this.isStatic = Modifier.isStatic(astNode.getModifiers());
        this.isPrivate = Modifier.isPrivate(astNode.getModifiers());
        this.initializer = null;
    }

    public void setInitializer(ExpressionBlock initializer) {
        this.initializer = initializer;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    /**
     * {@code Rect area = ⟨value⟩ ⊕} — the field's sentence, declared. The caption above it
     * ({@code Private Static Field}) and the cross beside that caption are <em>not</em> in the spec: they are
     * this block's own chrome on a row no compact renderer draws, and what the HUD wants is the sentence.
     *
     * <p>Unlike {@link VariableDeclarationBlock}, the name here <em>is</em> editable in place, because
     * {@code replaceSimpleName} on the field's fragment is the whole rename — a field has no Variables screen
     * standing between it and its uses. The type is likewise a click, through
     * {@code ExpressionMenu.installTypeSelector}.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .custom("type", () -> typeLabelNode(context))
                .custom("name", () -> nameFieldNode(context));

        if (initializer == null) {
            // "Set Value" writes an initializer, so a locked field must not offer it — like the delete and add
            // buttons, a read-only block gets no control at all rather than one that no-ops (or worse, an edit
            // the write layer then refuses). A read-only field with no value simply shows "type name".
            spec.picker("set-value", () -> isReadOnly() ? null : setValueButton(context));
        } else {
            spec.label("eq", () -> SentenceLayoutBuilder.keywordNode("="))
                    .slot("value", () -> initializerNode(context))
                    .picker("change", () -> createAddButton(e -> {
                        Expression current = (Expression) initializer.getAstNode();
                        ContextMenu menu = ExpressionMenu.create(
                                fieldType, false, context, this.astNode, x -> true,
                                selection -> applyExpressionSelection(context, current, selection));
                        menu.show((Button) e.getSource(), javafx.geometry.Side.BOTTOM, 0, 0);
                    }));
        }
        return spec.build();
    }

    /** The type chip, which opens the type selector unless the field is locked. */
    private Node typeLabelNode(CodeEditorService context) {
        Node typeLabel = TypeChip.of(((FieldDeclaration) this.astNode).getType());
        if (!isReadOnly()) {
            ExpressionMenu.installTypeSelector(typeLabel, "Click to change type", () -> fieldType,
                    context, this.astNode,
                    newTypeName -> context.getCodeEditor().replaceFieldType((FieldDeclaration) this.astNode, newTypeName.simpleName()));
        }
        return typeLabel;
    }

    /** The name, renamed in place: a field's fragment carries the only spelling there is. */
    private Node nameFieldNode(CodeEditorService context) {
        return TextFieldComponents.createVariableName(variableName, !isReadOnly(), newName -> {
            FieldDeclaration fieldDecl = (FieldDeclaration) this.astNode;
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) fieldDecl.fragments().getFirst();
            if (!newName.equals(variableName) && !newName.isEmpty()) {
                context.getCodeEditor().replaceSimpleName(fragment.getName(), newName);
            }
        });
    }

    private Button setValueButton(CodeEditorService context) {
        Button setValue = new Button("Set Value");
        setValue.getStyleClass().add("block-action-button");
        setValue.setOnAction(e ->
                context.getCodeEditor().setFieldInitializerToDefault((FieldDeclaration) this.astNode, fieldType));
        return setValue;
    }

    /**
     * Whatever stands for the value: a list, an array, a type-matched picker, or the expression's own node.
     *
     * <p>The picker is the field's declared type's, exactly as a local variable's and a call argument's are
     * (2026-09-19). Until then a field never asked, so {@code static final ImageTemplate COLLECT = new
     * ImageTemplate(…)} was drawn as the generic blue <i>Create</i> block while the SDK's picture editor for
     * the type went unused. On a field the canvas may not edit, the registry answers the plugin's preview.
     */
    private Node initializerNode(CodeEditorService context) {
        if (initializer instanceof ListBlock) return initializer.getUINode(context);
        if (initializer.getAstNode() instanceof ArrayInitializer) return createListDisplay(context);
        Node picker = PickerRegistry.pickerNodeFor(
                PickerContext.of(context, ValueSlot.of(initializer), fieldType, isReadOnly()));
        return picker != null ? picker : initializer.getUINode(context);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);

        Label modifiersLabel = new Label((isPrivate ? "Private" : "Public") + (isStatic ? " Static" : "") + " Field");
        modifiersLabel.getStyleClass().addAll("block-caption", "block-caption--strong");

        HBox headerRow = new HBox(10);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getChildren().addAll(modifiersLabel, BlockUIComponents.createSpacer());

        // Null when this block is read-only: createDeleteButton returns null rather than a disabled button,
        // so a locked field simply has no delete affordance. Styling it unconditionally NPE'd and aborted the
        // whole render pass (a generated file with a field showed no blocks at all).
        Button deleteBtn = createDeleteButton(context);
        if (deleteBtn != null) {
            deleteBtn.getStyleClass().add("block-icon-button");
            headerRow.getChildren().add(deleteBtn);
        }

        container.getChildren().addAll(headerRow, renderSpec(context));
        return container;
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "[", "]", true);
    }
}
