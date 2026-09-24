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
import org.eclipse.jdt.core.dom.CastExpression;

import java.util.List;

/**
 * {@code (T) x}, read as {@code ⟨x⟩ as T} — a value treated as another type: a {@code double} cut to an
 * {@code int}, an {@code Object} taken to be the {@code Point} it is. The type is a field, the value a slot.
 */
public class CastBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock operand;

    public CastBlock(String id, CastExpression astNode) {
        super(id, astNode);
    }

    public void setOperand(ExpressionBlock operand) { this.operand = operand; }

    @Override
    public List<CodeBlock> getChildren() {
        return operand == null ? List.of() : List.of(operand);
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder();
        addOperand(spec, "value", operand, context, ResolvedType.UNKNOWN);
        return spec.label("as", () -> SentenceLayoutBuilder.keywordNode("as"))
                .custom("type", () -> typeField(astNode, context))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
