package com.botmaker.studio.ui.render.components;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.text.Text;

/**
 * A dropdown as wide as the choice it shows, not as its longest choice.
 *
 * <p>A {@link ComboBox} sizes itself to the widest item in its list, so an operator dropdown reading "plus" was
 * as wide as "greater than or equal to" — 170px for a four-letter word, on every arithmetic and comparison
 * block. It is {@link FieldSizing}'s rule for dropdowns: a block's value is drawn at its own width. The list
 * that opens is still as wide as its items; only the closed control shrinks.
 *
 * <p>Measured off the skin's own parts — the chosen value's text in the button cell's font, plus the cell's
 * padding, and the arrow — so what the stylesheet gives them is what is measured. Not {@code cell.prefWidth}:
 * a list cell with no list view (which the button cell is) reports a preferred width of zero, and the dropdown
 * collapsed to its arrow. Before the skin exists there is nothing to measure and the stock width stands.
 */
public class FittedComboBox<T> extends ComboBox<T> {

    private final Text probe = new Text();

    public FittedComboBox() {
        // A new value is new text in the button cell; re-measure then, not on the next unrelated layout.
        valueProperty().addListener((obs, was, now) -> requestLayout());
    }

    @Override
    protected double computePrefWidth(double height) {
        Node cell = lookup(".list-cell");
        Node arrow = lookup(".arrow-button");
        if (!(cell instanceof Labeled label) || arrow == null) return super.computePrefWidth(height);
        probe.setFont(label.getFont());
        probe.setText(shown());
        double text = Math.ceil(probe.getLayoutBounds().getWidth());
        return snappedLeftInset() + label.snappedLeftInset() + text + label.snappedRightInset()
                + arrow.prefWidth(-1) + snappedRightInset();
    }

    /**
     * The text the closed dropdown shows, read off the value rather than off the button cell: the skin sets
     * the cell's text during its own layout, after this width was asked for, so the cell reads empty here.
     */
    private String shown() {
        T value = getValue();
        if (value == null) return getPromptText() == null ? "" : getPromptText();
        return getConverter() != null ? getConverter().toString(value) : String.valueOf(value);
    }
}
