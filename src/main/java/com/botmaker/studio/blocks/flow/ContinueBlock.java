package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ContinueStatement;

public class ContinueBlock extends AbstractStatementBlock {

    public ContinueBlock(String id, ContinueStatement astNode) {
        super(id, astNode);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    /** One word, like {@link BreakBlock} — two when it names the labelled loop it continues. */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("continue"));
        var label = ((ContinueStatement) astNode).getLabel();
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
