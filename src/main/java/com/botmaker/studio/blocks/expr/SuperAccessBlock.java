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
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code super.name} and {@code super.method(⟨args⟩)} — the parent class's field or method, reached past this
 * class's own. The member is fixed; each argument is a slot typed from the method it resolves to.
 */
public class SuperAccessBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private final List<ExpressionBlock> arguments = new ArrayList<>();

    public SuperAccessBlock(String id, Expression astNode) {
        super(id, astNode);
        if (!(astNode instanceof SuperFieldAccess) && !(astNode instanceof SuperMethodInvocation)) {
            throw new IllegalArgumentException("not a super access: " + astNode);
        }
    }

    public void addArgument(ExpressionBlock argument) { arguments.add(argument); }

    @Override
    public List<CodeBlock> getChildren() {
        return List.copyOf(arguments);
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        // `super`, then the name as a word of its own: no "." between them and no brackets round the arguments.
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("super", () -> SentenceLayoutBuilder.keywordNode("super"));
        if (astNode instanceof SuperFieldAccess field) {
            return spec.label("name", () -> SentenceLayoutBuilder.labelNode(field.getName().getIdentifier())).build();
        }
        SuperMethodInvocation call = (SuperMethodInvocation) astNode;
        spec.label("name", () -> SentenceLayoutBuilder.labelNode(call.getName().getIdentifier()));
        IMethodBinding method = call.resolveMethodBinding();
        ITypeBinding[] params = method == null ? null : method.getParameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            ResolvedType expected = params != null && i < params.length
                    ? ResolvedType.of(params[i]) : ResolvedType.UNKNOWN;
            addOperand(spec, "arg" + i, arguments.get(i), context, expected);
        }
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
