package com.botmaker.studio.blocks.loop;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.menu.TypePicker;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * The three-part {@code for}: {@code for int i from ⟨0⟩ while ⟨i < 10⟩ each time ⟨i++⟩}, then the body.
 *
 * <p>The common shape — one counter declared in the first part — is drawn as a name and a start value, the
 * name renamed with its uses inside the loop ({@code CodeEditor.renameScopedVariable}). Anything else in the
 * first part ({@code i = 0, j = n}, or nothing) is drawn as the expressions it is. A missing condition reads
 * {@code forever}, which is what {@code for (;;)} means.
 */
public class ClassicForBlock extends AbstractStatementBlock implements BlockWithChildren {

    private final List<ExpressionBlock> initializers = new ArrayList<>();
    private final List<ExpressionBlock> updaters = new ArrayList<>();
    private ExpressionBlock start;
    private ExpressionBlock condition;
    private BodyBlock body;

    public ClassicForBlock(String id, ForStatement astNode) {
        super(id, astNode);
    }

    /**
     * The single counter the first part declares ({@code int i = 0}), or {@code null} when the first part is
     * anything else. Decides whether the block draws a name and a start, or the expressions as written.
     */
    public static VariableDeclarationFragment counter(ForStatement loop) {
        if (loop.initializers().size() != 1) return null;
        if (!(loop.initializers().getFirst() instanceof VariableDeclarationExpression decl)) return null;
        if (decl.fragments().size() != 1) return null;
        return (VariableDeclarationFragment) decl.fragments().getFirst();
    }

    public void setStart(ExpressionBlock start) { this.start = start; }
    public void addInitializer(ExpressionBlock initializer) { initializers.add(initializer); }
    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void addUpdater(ExpressionBlock updater) { updaters.add(updater); }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (start != null) children.add(start);
        children.addAll(initializers);
        if (condition != null) children.add(condition);
        children.addAll(updaters);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.LOOPS;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ForStatement loop = (ForStatement) astNode;
        VariableDeclarationFragment counter = counter(loop);
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("for"));

        if (counter != null) {
            VariableDeclarationExpression declaration = (VariableDeclarationExpression) counter.getParent();
            spec.custom("type", () -> TypePicker.chip(declaration.getType(),
                            TypePicker.Options.of(TypePicker.Filter.ANY).withTypeArguments(), !isReadOnly(),
                            context, declaration,
                            text -> context.getCodeEditor().setExpressionType(declaration, text)))
                    .custom("name", () -> nameField(counter.getName(), context));
            if (counter.getInitializer() != null) {
                spec.label("from", () -> SentenceLayoutBuilder.labelNode("from"))
                        .slot("start", () -> SentenceLayoutBuilder.expressionSlotNode(start, context,
                                ResolvedType.UNKNOWN));
            }
        } else {
            for (int i = 0; i < initializers.size(); i++) {
                ExpressionBlock init = initializers.get(i);
                spec.slot("init" + i, () -> SentenceLayoutBuilder.expressionSlotNode(init, context,
                        ResolvedType.UNKNOWN));
            }
        }

        if (loop.getExpression() == null) {
            spec.label("forever", () -> SentenceLayoutBuilder.labelNode("forever"));
        } else {
            spec.label("while", () -> SentenceLayoutBuilder.labelNode("while"))
                    .slot("condition", () -> SentenceLayoutBuilder.expressionSlotNode(condition, context,
                            ResolvedType.BOOLEAN));
        }

        if (!updaters.isEmpty()) {
            spec.label("each", () -> SentenceLayoutBuilder.labelNode("each time"));
            for (int i = 0; i < updaters.size(); i++) {
                ExpressionBlock update = updaters.get(i);
                spec.slot("update" + i, () -> SentenceLayoutBuilder.expressionSlotNode(update, context,
                        ResolvedType.UNKNOWN));
            }
        }
        return spec.body("body", () -> createIndentedBody(body, context, "loop-body")).build();
    }

    private Node nameField(SimpleName name, CodeEditorService context) {
        return TextFieldComponents.createVariableName(name.getIdentifier(), !isReadOnly(),
                newName -> context.getCodeEditor().renameScopedVariable(name, newName));
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withDeleteButton(deleteAction(context))
                .withStyleClass("for-block")
                .build();
    }
}
