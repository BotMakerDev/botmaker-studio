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
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.List;

public class WhileBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock condition;
    private BodyBlock body;

    public WhileBlock(String id, WhileStatement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (condition != null) children.add(condition);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.LOOPS;
    }

    /** {@code while [condition] [+]}, then the body — the first spec here to declare one. */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("while"))
                .slot("condition", () -> SentenceLayoutBuilder.expressionSlotNode(condition, context, ResolvedType.BOOLEAN))
                // Built as a change button rather than through the header builder's own, so the handler can
                // capture the event source: the menu opens on the button that was clicked.
                .picker("change", () -> createAddButton(e ->
                        showExpressionMenuAndReplace(
                                (Button) e.getSource(),
                                context,
                                ResolvedType.BOOLEAN,
                                condition != null ? (Expression) condition.getAstNode() : null)))
                .body("body", () -> createIndentedBody(body, context, "loop-body"))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
