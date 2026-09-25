package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * A lambda — code handed over as a value: {@code (a, b) → ⟨value⟩} when its body is one expression, and
 * {@code (a, b) →} over a body to drop blocks into when it is a block. Each parameter is a name field renamed
 * with its uses in the body; a typed parameter shows its type.
 *
 * <p>A statement that ends in a lambda over a body ({@code whileFind(img, m -> { … })}) is the body-call
 * block's, which draws the call and the body as one; this is every other lambda — an argument to
 * {@code forEach}, a {@code Runnable} stored in a variable, a comparator.
 */
public class LambdaBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private ExpressionBlock expressionBody;
    private BodyBlock blockBody;

    public LambdaBlock(String id, LambdaExpression astNode) {
        super(id, astNode);
    }

    public void setExpressionBody(ExpressionBlock body) { this.expressionBody = body; }
    public void setBlockBody(BodyBlock body) { this.blockBody = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (expressionBody != null) children.add(expressionBody);
        if (blockBody != null) children.add(blockBody);
        return children;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        LambdaExpression lambda = (LambdaExpression) astNode;
        ComponentSpec.Builder spec = ComponentSpec.builder();
        List<?> parameters = lambda.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            Object parameter = parameters.get(i);
            SimpleName name = LambdaCallHandler.declaredName(parameter);
            if (parameter instanceof SingleVariableDeclaration typed) {
                spec.label("type" + i, () -> SentenceLayoutBuilder.labelNode(typed.getType().toString()));
            }
            spec.custom("param" + i, () -> TextFieldComponents.createVariableName(name.getIdentifier(),
                    !isReadOnly(), newName -> context.getCodeEditor().renameLambdaParameter(name, newName)));
        }
        spec.label("arrow", () -> SentenceLayoutBuilder.keywordNode("→"));
        if (blockBody != null) {
            spec.body("body", () -> LayoutComponents.createIndentedBody(blockBody.getUINode(context), "if-body"));
        } else {
            addOperand(spec, "value", expressionBody, context, returnType());
        }
        return spec.build();
    }

    /** What the lambda's body must give back, from the functional interface it implements. */
    private ResolvedType returnType() {
        IMethodBinding method = ((LambdaExpression) astNode).resolveMethodBinding();
        return method == null ? ResolvedType.UNKNOWN : ResolvedType.of(method.getReturnType());
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return blockBody != null ? renderSpecStacked(context).build() : renderSpec(context);
    }
}
