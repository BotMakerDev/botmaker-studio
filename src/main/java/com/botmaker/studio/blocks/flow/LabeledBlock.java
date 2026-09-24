package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.LabeledStatement;

import java.util.List;

/**
 * {@code outer:} over the statement it names — the loop a {@code break outer} or {@code continue outer} leaves.
 *
 * <p>The labelled statement is drawn by its own block, one row under the label, rather than folded into it: a
 * labelled {@code while} is an ordinary {@code while} that happens to have a name, and keeps all of its own
 * controls.
 */
public class LabeledBlock extends AbstractStatementBlock implements BlockWithChildren {

    private StatementBlock inner;

    public LabeledBlock(String id, LabeledStatement astNode) {
        super(id, astNode);
    }

    public void setInner(StatementBlock inner) { this.inner = inner; }

    /** The label, without its colon. */
    public String label() {
        return ((LabeledStatement) astNode).getLabel().getIdentifier();
    }

    @Override
    public List<CodeBlock> getChildren() {
        return inner == null ? List.of() : List.of(inner);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("label"))
                .label("name", () -> SentenceLayoutBuilder.labelNode(label() + ":"))
                .body("inner", () -> inner == null ? null : inner.getUINode(context))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
