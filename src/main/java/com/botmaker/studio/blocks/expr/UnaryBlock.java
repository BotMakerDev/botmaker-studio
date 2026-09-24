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
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;

import java.util.List;

/**
 * An operator with one operand, used as a value: {@code -x}, {@code +x}, {@code ~x}, and the four
 * {@code ++}/{@code --} forms — {@code ++i} counts up then reads, {@code i++} reads then counts up. The operator
 * is drawn where Java writes it, before or after, because that position is the whole difference between the
 * last two.
 *
 * <p>{@code !x} has a block of its own ({@link NotOperatorBlock}), and a {@code ++}/{@code --} standing alone as
 * a statement is the assignment block's.
 */
public class UnaryBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock operand;

    public UnaryBlock(String id, Expression astNode) {
        super(id, astNode);
        if (!(astNode instanceof PrefixExpression) && !(astNode instanceof PostfixExpression)) {
            throw new IllegalArgumentException("not a unary operator: " + astNode);
        }
    }

    public void setOperand(ExpressionBlock operand) { this.operand = operand; }

    /** The operator as Java spells it. */
    public String operator() {
        return astNode instanceof PrefixExpression prefix
                ? prefix.getOperator().toString()
                : ((PostfixExpression) astNode).getOperator().toString();
    }

    /** Whether the operator is written after its operand ({@code i++}). */
    public boolean isPostfix() {
        return astNode instanceof PostfixExpression;
    }

    @Override
    public List<CodeBlock> getChildren() {
        return operand == null ? List.of() : List.of(operand);
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        String op = operator();
        ResolvedType expected = "~".equals(op) ? ResolvedType.INT : ResolvedType.DOUBLE;
        ComponentSpec.Builder spec = ComponentSpec.builder();
        if (!isPostfix()) spec.label("operator", () -> SentenceLayoutBuilder.keywordNode(op));
        addOperand(spec, "operand", operand, context, expected);
        if (isPostfix()) spec.label("operator", () -> SentenceLayoutBuilder.keywordNode(op));
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
