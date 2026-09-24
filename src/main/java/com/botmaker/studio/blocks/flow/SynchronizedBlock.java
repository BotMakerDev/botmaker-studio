package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.SynchronizedStatement;

import java.util.ArrayList;
import java.util.List;

/** {@code synchronized on ⟨lock⟩}, then the body — run by one thread at a time per lock. */
public class SynchronizedBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock lock;
    private BodyBlock body;

    public SynchronizedBlock(String id, SynchronizedStatement astNode) {
        super(id, astNode);
    }

    public void setLock(ExpressionBlock lock) { this.lock = lock; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (lock != null) children.add(lock);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FLOW;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("synchronized"))
                .label("on", () -> SentenceLayoutBuilder.labelNode("on"))
                .slot("lock", () -> SentenceLayoutBuilder.expressionSlotNode(lock, context, ResolvedType.UNKNOWN))
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
