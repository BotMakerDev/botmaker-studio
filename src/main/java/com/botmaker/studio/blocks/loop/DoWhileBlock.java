package com.botmaker.studio.blocks.loop;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

public class DoWhileBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock condition;
    private BodyBlock body;

    public DoWhileBlock(String id, DoStatement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (body != null) children.add(body);
        if (condition != null) children.add(condition);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.LOOPS;
    }

    /**
     * {@code do}, the body, then {@code while [condition] [+]}.
     *
     * <p>The body is declared <em>between</em> two rows, which is the case that decides how a spec with bodies
     * is drawn at all: the canvas breaks its rows at each body, so the closing condition lands under it rather
     * than beside the word {@code do}. The HUD drops the body and draws the two rows as one line, which is the
     * same reading of the same declaration.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("do", () -> SentenceLayoutBuilder.keywordNode("do"))
                .body("body", () -> createIndentedBody(body, context, "loop-body"))
                .label("while", () -> SentenceLayoutBuilder.keywordNode("while"))
                .slot("condition", () -> SentenceLayoutBuilder.expressionSlotNode(condition, context, ResolvedType.BOOLEAN))
                .picker("change", () -> createAddButton(e ->
                        showExpressionMenuAndReplace(
                                (Button) e.getSource(),
                                context,
                                ResolvedType.BOOLEAN,
                                condition != null ? (Expression) condition.getAstNode() : null)))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
