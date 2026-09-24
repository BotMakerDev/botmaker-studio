package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.BreakStatement;

public class BreakBlock extends AbstractStatementBlock {

    public BreakBlock(String id, BreakStatement astNode) {
        super(id, astNode);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    /**
     * One word, and that is the whole block — the smallest spec there is. Two when it names the labelled
     * statement it leaves ({@code break outer}): drawn as a bare {@code break} it would read as leaving the
     * innermost loop, which is not what it does.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("break"));
        var label = ((BreakStatement) astNode).getLabel();
        if (label != null) spec.label("label", () -> SentenceLayoutBuilder.labelNode(label.getIdentifier()));
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
