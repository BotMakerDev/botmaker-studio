package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PrintBlock extends AbstractStatementBlock {

    private final List<ExpressionBlock> arguments = new ArrayList<>();

    public PrintBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
    }

    public void addArgument(ExpressionBlock argument) { this.arguments.add(argument); }

    @Override
    protected BlockCategory category() {
        return BlockCategory.OUTPUT;
    }

    /**
     * Print's sentence, declared: the word, one slot per argument, and the ⊕ that changes the value.
     *
     * <p>The first block to declare one, together with {@code ReturnBlock}. Nothing about what is drawn moves
     * — every node below is the one {@link BlockLayout#sentence()} built here before — only who says what the
     * sentence is made of, so the overlay HUD can draw the same parts at its own density.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder().label("kw", () -> SentenceLayoutBuilder.labelNode("Print:"));

        if (arguments.isEmpty()) {
            // UNKNOWN for the same reason the filled slot below uses it: println is overloaded for every type.
            spec.slot("value", () -> createEmptySlot(context, ResolvedType.UNKNOWN));
        } else {
            for (int i = 0; i < arguments.size(); i++) {
                // A slot, not a bare node: print's argument is the most obvious thing in the editor to drag a
                // value into, and it was the one expression that took no drops. UNKNOWN, not STRING — println
                // is overloaded for every type, so a number or a Point is as legal here as a string.
                ExpressionBlock arg = arguments.get(i);
                spec.slot("value" + i, () -> SentenceLayoutBuilder.expressionSlotNode(arg, context, ResolvedType.UNKNOWN));
            }
        }

        return spec.picker("add", () -> addButton(context)).build();
    }

    /** The ⊕, or null when this block is read-only — both renderers skip a null node. */
    private Button addButton(CodeEditorService context) {
        // Add Button with Filter (No List in Print)
        return createAddButton(e -> {
            Expression toReplace = !arguments.isEmpty() ?
                    (org.eclipse.jdt.core.dom.Expression) arguments.getFirst().getAstNode() : null;

            showExpressionMenuAndReplace(
                    (Button) e.getSource(),
                    context,
                    ResolvedType.UNKNOWN,
                    toReplace,
                    // Filter: Don't allow lists in print
                    expr -> expr != ExpressionCatalog.LIST
            );
        });
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withCustomNode(renderSpec(context))
                .withDeleteButton(deleteAction(context))
                .build();
    }

    @Override
    public String getDetails() {
        String argsString = arguments.stream().map(ExpressionBlock::getDetails).collect(Collectors.joining(", "));
        return "Print Statement: " + argsString;
    }
}
