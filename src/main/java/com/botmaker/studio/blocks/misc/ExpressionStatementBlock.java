package com.botmaker.studio.blocks.misc;

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
import org.eclipse.jdt.core.dom.ExpressionStatement;

import java.util.List;

/**
 * {@code do ⟨expression⟩} — an expression run for its effect that no statement block claims: {@code new
 * Thread(r);}, {@code super.stop();}. Calls, assignments and increments have blocks of their own and never
 * reach here.
 *
 * <p>The expression is an ordinary slot, so it is drawn by whatever expression block it is and can be replaced
 * like any other value.
 */
public class ExpressionStatementBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock expression;

    public ExpressionStatementBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
    }

    public void setExpression(ExpressionBlock expression) { this.expression = expression; }

    @Override
    public List<CodeBlock> getChildren() {
        return expression == null ? List.of() : List.of(expression);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FUNCTIONS;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("do"))
                .slot("expression", () ->
                        SentenceLayoutBuilder.expressionSlotNode(expression, context, ResolvedType.UNKNOWN))
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
