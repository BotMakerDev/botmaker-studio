package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.parser.handlers.TryHandler;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.TryStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code try}, its body, then a {@code catch ⟨type⟩ ⟨name⟩} row and body per clause, then {@code finally} and
 * its body — every part a drop target, and the clauses themselves added and removed from the block.
 *
 * <p>A clause's type is typed, not picked: exception types are an open set (the project's own, a library's),
 * and {@code IOException | InterruptedException} is written the way Java writes it. Its name is renamed with
 * its uses inside the clause only, because every {@code catch} in a method is {@code e}. The removal that
 * would leave a {@code try} with neither a {@code catch} nor a {@code finally} is refused by the editor
 * ({@link TryHandler}), so the block need not hide the cross that would do it.
 *
 * <p>Resources ({@code try (var in = open())}) are drawn as the expressions they are.
 */
public class TryBlock extends AbstractStatementBlock implements BlockWithChildren {

    private final List<ExpressionBlock> resources = new ArrayList<>();
    private final List<BodyBlock> catchBodies = new ArrayList<>();
    private BodyBlock tryBody;
    private BodyBlock finallyBody;

    public TryBlock(String id, TryStatement astNode) {
        super(id, astNode);
    }

    public void addResource(ExpressionBlock resource) { resources.add(resource); }
    public void setTryBody(BodyBlock body) { this.tryBody = body; }
    /** The body of the next {@code catch}, in source order. */
    public void addCatchBody(BodyBlock body) { catchBodies.add(body); }
    public void setFinallyBody(BodyBlock body) { this.finallyBody = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>(resources);
        if (tryBody != null) children.add(tryBody);
        children.addAll(catchBodies);
        if (finallyBody != null) children.add(finallyBody);
        return children;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FLOW;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        TryStatement tryStmt = (TryStatement) astNode;
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("try"));
        if (!resources.isEmpty()) {
            spec.label("with", () -> SentenceLayoutBuilder.labelNode("with"));
            for (int i = 0; i < resources.size(); i++) {
                ExpressionBlock resource = resources.get(i);
                spec.slot("resource" + i, () -> SentenceLayoutBuilder.expressionSlotNode(resource, context,
                        ResolvedType.UNKNOWN));
            }
        }
        spec.body("try-body", () -> createIndentedBody(tryBody, context, "if-body"));

        List<?> clauses = tryStmt.catchClauses();
        for (int i = 0; i < clauses.size() && i < catchBodies.size(); i++) {
            CatchClause clause = (CatchClause) clauses.get(i);
            BodyBlock catchBody = catchBodies.get(i);
            int index = i;
            spec.label("catch-kw" + i, () -> SentenceLayoutBuilder.keywordNode("catch"))
                    .custom("catch-type" + i, () -> TextFieldComponents.createVariableName(
                            TryHandler.catchTypeText(clause), !isReadOnly(),
                            typeText -> context.getCodeEditor().setCatchType(clause, typeText)))
                    .label("catch-as" + i, () -> SentenceLayoutBuilder.labelNode("as"))
                    .custom("catch-name" + i, () -> TextFieldComponents.createVariableName(
                            clause.getException().getName().getIdentifier(), !isReadOnly(),
                            newName -> context.getCodeEditor()
                                    .renameScopedVariable(clause.getException().getName(), newName)))
                    .custom("catch-spacer" + i, BlockUIComponents::createSpacer)
                    .picker("catch-delete" + i, () -> isReadOnly() ? null : BlockUIComponents.createDeleteButton(
                            () -> context.getCodeEditor().deleteCatchClause(tryStmt, index)))
                    .body("catch-body" + i, () -> createIndentedBody(catchBody, context, "if-body"));
        }

        if (finallyBody != null) {
            spec.label("finally-kw", () -> SentenceLayoutBuilder.keywordNode("finally"))
                    .custom("finally-spacer", BlockUIComponents::createSpacer)
                    .picker("finally-delete", () -> isReadOnly() ? null : BlockUIComponents.createDeleteButton(
                            () -> context.getCodeEditor().deleteFinallyClause(tryStmt)))
                    .body("finally-body", () -> createIndentedBody(finallyBody, context, "if-body"));
        }

        spec.picker("add-catch", () -> addButton("catch", "Add a catch clause",
                () -> context.getCodeEditor().addCatchClause(tryStmt)));
        if (finallyBody == null) {
            spec.picker("add-finally", () -> addButton("finally", "Add a finally clause",
                    () -> context.getCodeEditor().addFinallyClause(tryStmt)));
        }
        return spec.build();
    }

    /**
     * A "+ catch" / "+ finally" button, or null on a locked block. The enum block's "+ constant" style: an
     * {@code icon-button} is a fixed 18px glyph and cut the word to "+…".
     */
    private Button addButton(String word, String tip, Runnable action) {
        if (isReadOnly()) return null;
        Button button = new Button("+ " + word);
        button.getStyleClass().addAll("block-action-button", "block-action-button--mini");
        button.setOnAction(e -> action.run());
        Tooltip.install(button, new Tooltip(tip));
        return button;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withStyleClass("try-block")
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
