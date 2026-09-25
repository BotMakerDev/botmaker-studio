package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.TypeLiteral;

/** {@code Point.class} — a class handed over as a value, as a logger or a lookup by type takes one. */
public class TypeLiteralBlock extends AbstractExpressionBlock {

    public TypeLiteralBlock(String id, TypeLiteral astNode) {
        super(id, astNode);
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .custom("type", () -> typeField(astNode, context))
                .label("class", () -> SentenceLayoutBuilder.keywordNode("class"))
                .build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
