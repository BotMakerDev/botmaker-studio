package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ArrayCreation;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code new T[n]}, read as {@code new list of T, size ⟨n⟩} — an array of a given length, every element at its
 * default. A second dimension reads {@code by ⟨m⟩}, and one left open ({@code new int[3][]}) reads
 * {@code by …}. An array written with its elements ({@code new int[] {1, 2}}) is a list block instead.
 */
public class ArrayCreationBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private final List<ExpressionBlock> dimensions = new ArrayList<>();

    public ArrayCreationBlock(String id, ArrayCreation astNode) {
        super(id, astNode);
    }

    public void addDimension(ExpressionBlock dimension) { dimensions.add(dimension); }

    @Override
    public List<CodeBlock> getChildren() {
        return List.copyOf(dimensions);
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("new", () -> SentenceLayoutBuilder.keywordNode("new list of"))
                .custom("type", () -> typeField(astNode, context))
                .label("size", () -> SentenceLayoutBuilder.keywordNode("size"));
        int declared = ((ArrayCreation) astNode).getType().getDimensions();
        for (int i = 0; i < declared; i++) {
            if (i > 0) spec.label("by" + i, () -> SentenceLayoutBuilder.keywordNode("by"));
            if (i < dimensions.size()) {
                addOperand(spec, "dimension" + i, dimensions.get(i), context, ResolvedType.INT);
            } else {
                spec.label("open" + i, () -> SentenceLayoutBuilder.labelNode("…"));
            }
        }
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
