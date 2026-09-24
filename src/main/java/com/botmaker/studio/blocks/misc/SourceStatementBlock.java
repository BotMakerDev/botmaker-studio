package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.eclipse.jdt.core.dom.Statement;

/**
 * A statement drawn as the Java it is — the block for every statement no other block draws.
 *
 * <p><b>Why this exists.</b> {@code BlockConverter.dispatchStatement} ended in {@code Optional.empty()}, and its
 * callers drop an empty: a {@code throw}, a classic {@code for}, a {@code try}, a local class — each was in the
 * file and absent from the canvas, and since the blocks are also what an edit is written from, one edit away
 * from being written out of it. {@code UnknownExpressionBlock} closed the same hole for expressions; this closes
 * it for statements, with one difference: a statement is edited here as text. Its ✎ swaps the source for an
 * editor, and what is typed replaces the statement if it is Java statements
 * ({@code CodeEditor.replaceStatementSource}) and is refused, with a status line, if it is not.
 *
 * <p>Most statements have a block of their own now; what reaches here is what cannot be drawn faithfully as
 * one — a local class, several variables in one declaration, a bare body in a locked file. The
 * {@code reason} says which, on the tooltip.
 */
public class SourceStatementBlock extends AbstractStatementBlock {

    private final String source;
    private final String reason;

    /**
     * @param source the statement's text as written, cut from the file (never re-printed from the tree)
     * @param reason one sentence on why this statement is shown as source
     */
    public SourceStatementBlock(String id, Statement astNode, String source, String reason) {
        super(id, astNode);
        this.source = source == null ? String.valueOf(astNode).strip() : source.strip();
        this.reason = reason;
    }

    /** The statement's text as the file has it. */
    public String getSource() { return source; }

    /** Why this statement is drawn as source rather than as a block of its own. */
    public String getReason() { return reason; }

    @Override
    protected BlockCategory category() {
        return BlockCategory.UTILITY;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("source-statement-row");
        Label badge = new Label("</>");
        badge.getStyleClass().add("keyword-label");
        row.getChildren().add(badge);
        showSource(row, context);

        return BlockLayout.header()
                .withGrowingNode(row)
                .withDeleteButton(deleteAction(context))
                .build();
    }

    private void showSource(HBox row, CodeEditorService context) {
        row.getChildren().remove(1, row.getChildren().size());   // keep the </> badge
        Label text = new Label(source);
        text.getStyleClass().add("source-statement-text");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        Tooltip.install(text, new Tooltip(reason + "\nIt is kept exactly as written."));
        row.getChildren().add(text);

        if (!isReadOnly()) {
            Button edit = new Button("✎");
            edit.getStyleClass().add("icon-button");
            Tooltip.install(edit, new Tooltip("Edit this statement as Java"));
            edit.setOnAction(e -> showEditor(row, context));
            row.getChildren().add(edit);
        }
    }

    /** The source in a wrapping editor; commits on focus loss, and returns to the read view either way. */
    private void showEditor(HBox row, CodeEditorService context) {
        row.getChildren().remove(1, row.getChildren().size());
        TextArea editor = TextFieldComponents.createCommentEditArea(source, "Java statement", newText -> {
            if (!newText.strip().equals(source)) {
                Platform.runLater(() ->
                        context.getCodeEditor().replaceStatementSource((Statement) astNode, newText));
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
