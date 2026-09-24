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
import org.eclipse.jdt.core.dom.YieldStatement;

import java.util.List;

/** {@code yield ⟨value⟩} — the value a {@code switch} expression's braced arm produces. */
public class YieldBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock value;

    public YieldBlock(String id, YieldStatement astNode) {
        super(id, astNode);
    }

    public void setValue(ExpressionBlock value) { this.value = value; }

    @Override
    public List<CodeBlock> getChildren() {
        return value == null ? List.of() : List.of(value);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("yield"))
                .slot("value", () -> SentenceLayoutBuilder.expressionSlotNode(value, context, ResolvedType.UNKNOWN))
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
