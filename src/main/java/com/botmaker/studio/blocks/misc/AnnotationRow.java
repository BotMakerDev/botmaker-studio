package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.services.CodeEditorService;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * A declaration's annotations, drawn as a row of pills above its header (2026-09-27).
 *
 * <p>The canvas drew none until then, except a count of review entries beside a function's name — so a
 * {@code @SdkValue(SdkValue.Id.FLOW)}, an {@code @Override} or a hand-written {@code @SuppressWarnings} was
 * invisible on the one screen that claims to show the whole file. Each annotation is now a pill spelled as the
 * source spells it:
 *
 * <ul>
 *   <li><b>{@code @Param} and a plugin's managed mark are the host's</b>: they are edited in the Parameters window and
 *       the plugin's own window, so their pill says where and has no ✕.</li>
 *   <li><b>{@code @Refactor}</b> reads as what it records — how many guesses wait for review, or that it was
 *       reviewed (dimmed) — with each sentence in the tooltip. It is the user's to delete.</li>
 *   <li>Everything else is the user's: a ✕ removes it, unless the block is read-only.</li>
 * </ul>
 */
public final class AnnotationRow {

    private static final PseudoClass DONE = PseudoClass.getPseudoClass("done");

    /** A pill longer than this is cut with an ellipsis; the tooltip holds the whole of it. */
    private static final double MAX_PILL_WIDTH = 420;

    private AnnotationRow() {}

    /** The row for {@code declaration}, or null when it carries no annotation. */
    public static Node of(BodyDeclaration declaration, CodeEditorService context, boolean editable) {
        List<Annotation> annotations = new ArrayList<>();
        for (Object modifier : declaration.modifiers()) {
            if (modifier instanceof Annotation annotation) annotations.add(annotation);
        }
        if (annotations.isEmpty()) return null;

        FlowPane row = new FlowPane(6, 4);
        row.getStyleClass().add("annotation-row");
        for (Annotation annotation : annotations) row.getChildren().add(pill(declaration, annotation, context, editable));
        return row;
    }

    private static Node pill(BodyDeclaration owner, Annotation annotation, CodeEditorService context,
                             boolean editable) {
        HBox pill = new HBox(4);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.getStyleClass().add("annotation-pill");

        Label text = new Label();
        text.setMaxWidth(MAX_PILL_WIDTH);
        String written = annotation.toString();
        boolean deletable = editable;

        if (BotAnnotation.PARAM.marks(annotation) || ManagedIds.marks(annotation, PluginHost.managedValues())) {
            pill.getStyleClass().add("annotation-pill--host");
            text.setText(written);
            text.setTooltip(new Tooltip(written + "\n\n" + (BotAnnotation.PARAM.marks(annotation)
                    ? "A parameter: change it in the Parameters window."
                    : "A plugin's value: change it in that plugin's own window.")));
            deletable = false;
        } else if (BotAnnotation.REFACTOR.marks(annotation) && owner instanceof MethodDeclaration method
                && ReviewMarks.markOn(method) != null) {
            ReviewMarks.Mark mark = ReviewMarks.markOn(method);
            int count = mark.entries().size();
            pill.getStyleClass().add("annotation-pill--refactor");
            pill.pseudoClassStateChanged(DONE, mark.done());
            text.setText(mark.done() ? "✓ @Refactor · reviewed"
                    : "⚑ @Refactor · " + count + " to review");
            text.setTooltip(new Tooltip(String.join("\n\n", mark.entries())
                    + (mark.done() ? "" : "\n\nOpen the Review tab to mark it reviewed.")));
        } else {
            text.setText(written);
            text.setTooltip(new Tooltip(written));
        }
        pill.getChildren().add(text);

        if (deletable) {
            Button remove = new Button("×");
            remove.getStyleClass().add("annotation-pill-remove");
            remove.setTooltip(new Tooltip("Remove this annotation"));
            remove.setOnAction(e -> context.getCodeEditor().removeAnnotation(owner, annotation));
            pill.getChildren().add(remove);
        }
        return pill;
    }
}
