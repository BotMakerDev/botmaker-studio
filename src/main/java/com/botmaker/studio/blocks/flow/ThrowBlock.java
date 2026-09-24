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
import org.eclipse.jdt.core.dom.ThrowStatement;

import java.util.List;

/** {@code throw ⟨exception⟩} — stops here and hands the exception to whichever {@code catch} is waiting. */
public class ThrowBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock exception;

    public ThrowBlock(String id, ThrowStatement astNode) {
        super(id, astNode);
    }

    public void setException(ExpressionBlock exception) { this.exception = exception; }

    @Override
    public List<CodeBlock> getChildren() {
        return exception == null ? List.of() : List.of(exception);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("throw"))
                .slot("exception", () ->
                        SentenceLayoutBuilder.expressionSlotNode(exception, context, ResolvedType.UNKNOWN))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withCustomNode(renderSpec(context))
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
