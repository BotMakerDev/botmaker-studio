package com.botmaker.studio.blocks.func;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code this(⟨args⟩)} or {@code super(⟨args⟩)} — the first line of a constructor that hands its work to another
 * constructor. Each argument is a slot typed from the constructor it resolves to, when it resolves.
 */
public class ConstructorCallBlock extends AbstractStatementBlock implements BlockWithChildren {

    private final List<ExpressionBlock> arguments = new ArrayList<>();

    public ConstructorCallBlock(String id, Statement astNode) {
        super(id, astNode);
        if (!(astNode instanceof ConstructorInvocation) && !(astNode instanceof SuperConstructorInvocation)) {
            throw new IllegalArgumentException("not a constructor call: " + astNode);
        }
    }

    public void addArgument(ExpressionBlock argument) { arguments.add(argument); }

    /** Whether this is {@code super(…)} rather than {@code this(…)}. */
    public boolean isSuper() {
        return astNode instanceof SuperConstructorInvocation;
    }

    @Override
    public List<CodeBlock> getChildren() {
        return List.copyOf(arguments);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FUNCTIONS;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode(isSuper() ? "super" : "this"))
                .label("open", () -> SentenceLayoutBuilder.labelNode("("));
        ITypeBinding[] params = parameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            ExpressionBlock arg = arguments.get(i);
            ResolvedType expected = params != null && i < params.length
                    ? ResolvedType.of(params[i]) : ResolvedType.UNKNOWN;
            if (i > 0) spec.label("comma" + i, () -> SentenceLayoutBuilder.labelNode(","));
            spec.slot("arg" + i, () -> SentenceLayoutBuilder.expressionSlotNode(arg, context, expected));
        }
        return spec.label("close", () -> SentenceLayoutBuilder.labelNode(")")).build();
    }

    private ITypeBinding[] parameterTypes() {
        IMethodBinding binding = astNode instanceof SuperConstructorInvocation s
                ? s.resolveConstructorBinding()
                : ((ConstructorInvocation) astNode).resolveConstructorBinding();
        return binding == null ? null : binding.getParameterTypes();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withCustomNode(renderSpec(context))
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
