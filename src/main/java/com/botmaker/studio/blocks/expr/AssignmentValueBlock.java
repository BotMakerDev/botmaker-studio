package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.blocks.var.AssignmentBlock;
import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.SelectorComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.Assignment;

import java.util.ArrayList;
import java.util.List;

/**
 * An assignment used as a value — {@code while ((line = reader.readLine()) != null)}, {@code a = b = 0}: it
 * stores, then hands on what it stored. Drawn as the assignment block's sentence, {@code ⟨target⟩ set to
 * ⟨value⟩}, with the same operator choices; as a statement it is {@link AssignmentBlock}.
 */
public class AssignmentValueBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock target;
    private ExpressionBlock value;

    public AssignmentValueBlock(String id, Assignment astNode) {
        super(id, astNode);
    }

    public void setTarget(ExpressionBlock target) { this.target = target; }
    public void setValue(ExpressionBlock value) { this.value = value; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (target != null) children.add(target);
        if (value != null) children.add(value);
        return children;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        Assignment assignment = (Assignment) astNode;
        String operator = assignment.getOperator().toString();
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .slot("target", () -> SentenceLayoutBuilder.expressionSlotNode(target, context, ResolvedType.UNKNOWN))
                .picker("operator", () -> isReadOnly()
                        ? SentenceLayoutBuilder.keywordNode(AssignmentBlock.operatorName(operator))
                        : SelectorComponents.createOperatorSelector(AssignmentBlock.ASSIGN_NAMES,
                                AssignmentBlock.ASSIGN_SYMBOLS, operator,
                                symbol -> context.getCodeEditor().updateAssignmentOperator(assignment, symbol)));
        ResolvedType targetType = assignment.getLeftHandSide().resolveTypeBinding() == null
                ? ResolvedType.UNKNOWN : ResolvedType.of(assignment.getLeftHandSide().resolveTypeBinding());
        addOperand(spec, "value", value, context, targetType);
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
