package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.PatternInstanceofExpression;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypePattern;

import java.util.List;

/**
 * {@code x instanceof T}, read as {@code ⟨x⟩ is a T}, and its pattern form {@code x instanceof T name}, read as
 * {@code ⟨x⟩ is a T called name} — the check that also names the value as that type for what follows.
 *
 * <p>The name is renamed with its uses in the block the check sits in ({@code CodeEditor.renamePatternVariable}),
 * since a pattern variable's scope follows the flow: after {@code if (!(o instanceof Point p)) return;} it is in
 * scope to the block's end. A record pattern ({@code o instanceof Point(int x, int y)}) is shown as written.
 */
public class InstanceofBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock operand;

    public InstanceofBlock(String id, Expression astNode) {
        super(id, astNode);
        if (!(astNode instanceof InstanceofExpression) && !(astNode instanceof PatternInstanceofExpression)) {
            throw new IllegalArgumentException("not an instanceof: " + astNode);
        }
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
        spec.label("is", () -> SentenceLayoutBuilder.keywordNode("is a"));
        if (astNode instanceof PatternInstanceofExpression check) {
            if (check.getPattern() instanceof TypePattern pattern
                    && pattern.getPatternVariable() instanceof SingleVariableDeclaration variable) {
                spec.custom("type", () -> typeField(astNode, context))
                        .label("called", () -> SentenceLayoutBuilder.keywordNode("called"))
                        .custom("name", () -> TextFieldComponents.createVariableName(
                                variable.getName().getIdentifier(), !isReadOnly(),
                                name -> context.getCodeEditor().renamePatternVariable(variable.getName(), name)));
            } else {
                spec.label("pattern", () -> SentenceLayoutBuilder.labelNode(String.valueOf(check.getPattern())));
            }
        } else {
            spec.custom("type", () -> typeField(astNode, context));
        }
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
