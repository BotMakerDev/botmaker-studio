package com.botmaker.studio.blocks.flow;

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
import org.eclipse.jdt.core.dom.AssertStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code assert ⟨condition⟩ else fail with ⟨message⟩} — a check that stops the bot when it is false, and only
 * when the JVM runs with {@code -ea}. The message part is drawn only when the source has one.
 */
public class AssertBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock condition;
    private ExpressionBlock message;

    public AssertBlock(String id, AssertStatement astNode) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setMessage(ExpressionBlock message) { this.message = message; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (condition != null) children.add(condition);
        if (message != null) children.add(message);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("assert"))
                .slot("condition", () ->
                        SentenceLayoutBuilder.expressionSlotNode(condition, context, ResolvedType.BOOLEAN));
        if (((AssertStatement) astNode).getMessage() != null) {
            spec.label("else", () -> SentenceLayoutBuilder.labelNode("else fail with"))
                    .slot("message", () ->
                            SentenceLayoutBuilder.expressionSlotNode(message, context, ResolvedType.UNKNOWN));
        }
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withCustomNode(renderSpec(context))
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
