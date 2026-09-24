package com.botmaker.studio.ui.render.components;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.TextField;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * A field on a block as wide as what it holds.
 *
 * <p>A {@link TextField} is twelve columns wide unless told otherwise, whatever it contains — so a {@code 0}
 * sat in a 150px box, every sentence holding a number was 150px longer than its words, and those boxes were
 * most of the width that made a row refuse to fit a narrow (or zoomed-in) canvas. A block value is a token,
 * like the words around it, and is drawn at its own width: the text, or the prompt when there is none.
 */
public final class FieldSizing {

    /** Narrow enough for one digit, wide enough to click into. */
    static final double MIN_WIDTH = 28;
    /** Past this a value scrolls inside its field rather than pushing the sentence off the canvas. */
    static final double MAX_WIDTH = 320;
    /** Room for the caret after the last character, so typing does not scroll the first one out of view. */
    private static final double CARET_ROOM = 8;

    private FieldSizing() {}

    /** Binds {@code field}'s preferred width to its text (or prompt), within {@link #MIN_WIDTH}..{@link #MAX_WIDTH}. */
    public static void fitToText(TextField field) {
        Text probe = new Text();
        field.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> width(probe, field.getText(), field.getPromptText(), field.getFont(), field.getPadding()),
                field.textProperty(), field.promptTextProperty(), field.fontProperty(), field.paddingProperty()));
    }

    static double width(Text probe, String text, String prompt, Font font, Insets padding) {
        String shown = text == null || text.isEmpty() ? prompt : text;
        probe.setFont(font);
        probe.setText(shown == null ? "" : shown);
        double measured = probe.getLayoutBounds().getWidth() + CARET_ROOM
                + (padding == null ? 0 : padding.getLeft() + padding.getRight());
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.ceil(measured)));
    }
}
