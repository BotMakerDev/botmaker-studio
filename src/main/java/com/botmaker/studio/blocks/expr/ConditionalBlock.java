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
import org.eclipse.jdt.core.dom.ConditionalExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code c ? a : b}, read as {@code if ⟨c⟩ then ⟨a⟩ else ⟨b⟩} — the value that is one of two, decided by a
 * yes/no. Both branches are slots of the type the whole expression has, so the menu offers what fits.
 */
public class ConditionalBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock condition;
    private ExpressionBlock whenTrue;
    private ExpressionBlock whenFalse;

    public ConditionalBlock(String id, ConditionalExpression astNode) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setWhenTrue(ExpressionBlock whenTrue) { this.whenTrue = whenTrue; }
    public void setWhenFalse(ExpressionBlock whenFalse) { this.whenFalse = whenFalse; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (condition != null) children.add(condition);
        if (whenTrue != null) children.add(whenTrue);
        if (whenFalse != null) children.add(whenFalse);
        return children;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ResolvedType type = ownType();
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("if", () -> SentenceLayoutBuilder.keywordNode("if"));
        addOperand(spec, "condition", condition, context, ResolvedType.BOOLEAN);
        spec.label("then", () -> SentenceLayoutBuilder.keywordNode("then"));
        addOperand(spec, "when-true", whenTrue, context, type);
        spec.label("else", () -> SentenceLayoutBuilder.keywordNode("else"));
        addOperand(spec, "when-false", whenFalse, context, type);
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
