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
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.util.ArrayList;
import java.util.List;

/** {@code a[i]}, read as {@code item ⟨i⟩ of ⟨a⟩} — one element of an array, counted from 0. */
public class ArrayAccessBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock array;
    private ExpressionBlock index;

    public ArrayAccessBlock(String id, ArrayAccess astNode) {
        super(id, astNode);
    }

    public void setArray(ExpressionBlock array) { this.array = array; }
    public void setIndex(ExpressionBlock index) { this.index = index; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (array != null) children.add(array);
        if (index != null) children.add(index);
        return children;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("item", () -> SentenceLayoutBuilder.keywordNode("item"));
        addOperand(spec, "index", index, context, ResolvedType.INT);
        spec.label("of", () -> SentenceLayoutBuilder.keywordNode("of"));
        addOperand(spec, "array", array, context, arrayType());
        return spec.build();
    }

    /** The array's own type, so the menu offers arrays of the same element type. */
    private ResolvedType arrayType() {
        ITypeBinding binding = ((ArrayAccess) astNode).getArray().resolveTypeBinding();
        return binding == null ? ResolvedType.UNKNOWN : ResolvedType.of(binding);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
