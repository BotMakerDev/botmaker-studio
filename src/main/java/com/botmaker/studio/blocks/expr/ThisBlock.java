package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ThisExpression;

/** {@code this}, or {@code Outer.this} — the object the running method belongs to. Nothing in it to edit. */
public class ThisBlock extends AbstractExpressionBlock {

    public ThisBlock(String id, ThisExpression astNode) {
        super(id, astNode);
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ThisExpression self = (ThisExpression) astNode;
        String text = self.getQualifier() == null ? "this" : self.getQualifier() + ".this";
        return ComponentSpec.builder()
                .label("this", () -> SentenceLayoutBuilder.keywordNode(text))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
