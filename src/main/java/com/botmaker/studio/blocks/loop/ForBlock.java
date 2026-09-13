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
import com.botmaker.studio.ui.render.layout.BodyLayoutBuilder;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.List;

public class ForBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock variable;
    private ExpressionBlock collection;
    private BodyBlock body;

    public ForBlock(String id, EnhancedForStatement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
    }

    public void setVariable(ExpressionBlock variable) { this.variable = variable; }
    public void setCollection(ExpressionBlock collection) { this.collection = collection; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (variable != null) children.add(variable);
        if (collection != null) children.add(collection);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.LOOPS;
    }

    /**
     * {@code for each [name] in [collection]}, then the body.
     *
     * <p>The loop variable is a {@code CUSTOM} component rather than a slot: it is a name being declared here,
     * not a value standing in a slot, so nothing may be dropped onto it and it is edited as text.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        return ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("for each"))
                .custom("name", () -> nameField(context))
                .label("in", () -> SentenceLayoutBuilder.keywordNode("in"))
                .slot("collection", () -> SentenceLayoutBuilder.expressionSlotNode(collection, context, ResolvedType.UNKNOWN))
                .body("body", () -> BodyLayoutBuilder.bodyPane(body, context))
                .build();
    }

    /** The editable loop-variable name. */
    private Node nameField(CodeEditorService context) {
        // Extract variable name safely
        String varName = "";
        if (variable != null && variable.getAstNode() instanceof SimpleName) {
            varName = ((SimpleName) variable.getAstNode()).getIdentifier();
        }

        return TextFieldComponents.createVariableName(varName, !isReadOnly(), newName -> {
            if (variable != null && variable.getAstNode() instanceof SimpleName) {
                // Rename the declaration AND its references in the loop body — a plain replaceSimpleName renames
                // only the declaration, leaving the body on the old name so the code stops compiling.
                context.getCodeEditor().renameForEachVariable((SimpleName) variable.getAstNode(), newName);
            }
        });
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withDeleteButton(deleteAction(context))
                .withStyleClass("for-block")
                .withRowStyleClass("for-header")
                .build();
    }
}
