package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.Expression;

/**
 * A value drawn as the Java it is, by design — the expression twin of {@code SourceStatementBlock}.
 *
 * <p>What reaches here is not an expression nobody wrote a block for (that is {@link UnknownExpressionBlock},
 * and it means a gap) but one a block cannot draw faithfully: an anonymous class, whose body is a class; a
 * pattern; a switch expression written with {@code case X:} labels. The {@code reason} says which on the
 * tooltip, and ✎ edits the text: what is typed replaces the value if it is one Java expression
 * ({@code CodeEditor.replaceExpressionSource}) and is refused, with a status line, if it is not.
 */
public class SourceExpressionBlock extends AbstractExpressionBlock {

    private final String source;
    private final String reason;

    /**
     * @param source the expression's text as written, cut from the file
     * @param reason one sentence on why this value is shown as source
     */
    public SourceExpressionBlock(String id, Expression astNode, String source, String reason) {
        super(id, astNode);
        this.source = source == null ? String.valueOf(astNode).strip() : source.strip();
        this.reason = reason;
    }

    public String getSource() { return source; }

    public String getReason() { return reason; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        showSource(row, context);
        return row;
    }

    private void showSource(HBox row, CodeEditorService context) {
        row.getChildren().clear();
        Label text = new Label(source);
        text.getStyleClass().add("source-statement-text");
        text.setWrapText(true);
        Tooltip.install(text, new Tooltip(reason + "\nIt is kept exactly as written."));
        row.getChildren().add(text);
        if (!isReadOnly()) {
            Button edit = new Button("✎");
            edit.getStyleClass().add("icon-button");
            Tooltip.install(edit, new Tooltip("Edit this value as Java"));
            edit.setOnAction(e -> showEditor(row, context));
            row.getChildren().add(edit);
        }
    }

    private void showEditor(HBox row, CodeEditorService context) {
        row.getChildren().clear();
        TextArea editor = TextFieldComponents.createCommentEditArea(source, "Java expression", newText -> {
            if (!newText.strip().equals(source)) {
                Platform.runLater(() ->
                        context.getCodeEditor().replaceExpressionSource((Expression) astNode, newText));
            }
            Platform.runLater(() -> showSource(row, context));
        });
        editor.getStyleClass().add("source-statement-editor");
        editor.setPrefRowCount(Math.max(2, (int) source.lines().count()));
        row.getChildren().add(editor);
        editor.requestFocus();
    }

    @Override
    public String getDetails() {
        return "Java: " + source;
    }
}
