package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentSpec;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link ComponentSpec} containing <b>bodies</b>, drawn at canvas density: sentence rows stacked with each
 * body between them, which is how a control-flow block has always looked here.
 *
 * <p>{@link ComponentLayoutBuilder} draws a spec that is one sentence. This draws one that is not — a
 * {@code while} is a row then a body, a {@code do/while} is a row, a body, and another row — and the split is
 * simply "break the row at every {@link BlockComponent.Kind#BODY}". Nothing else about the two differs: both
 * take their visible components from {@link ComponentLayoutBuilder#render}, so a component's verdict cannot
 * depend on which of them is drawing it.
 *
 * <p>The <b>first</b> row is the header — it carries the delete cross — wherever it falls in the spec. A
 * {@code do/while} declares its body before its condition, so its first row is the word {@code do}, exactly as
 * the block built by hand before.
 *
 * <p>This is the counterpart of {@code CompactSpecRow}'s body rule rather than a contradiction of it: the HUD
 * drops bodies because its tree already shows branches as nested rows, and the canvas draws them because it
 * has no such tree. One declaration, two honest readings.
 */
public final class StackLayoutBuilder {

    private final ComponentSpec spec;
    private final Audience audience;
    private final boolean locked;
    private final List<String> styleClasses = new ArrayList<>();
    private final List<String> rowStyleClasses = new ArrayList<>();
    private Runnable onDelete;
    private double spacing = 5.0;
    private double rowSpacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;

    StackLayoutBuilder(ComponentSpec spec, Audience audience, boolean locked) {
        this.spec = spec == null ? ComponentSpec.empty() : spec;
        this.audience = audience == null ? Audience.EDITOR : audience;
        this.locked = locked;
    }

    /** The cross on the header row. Null — which is what a locked block's delete action is — omits it. */
    public StackLayoutBuilder withDeleteButton(Runnable onDelete) {
        this.onDelete = onDelete;
        return this;
    }

    /** Style classes for the whole block. */
    public StackLayoutBuilder withStyleClass(String... classes) {
        styleClasses.addAll(Arrays.asList(classes));
        return this;
    }

    /** Style classes for the header row's sentence pane. */
    public StackLayoutBuilder withRowStyleClass(String... classes) {
        rowStyleClasses.addAll(Arrays.asList(classes));
        return this;
    }

    public StackLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    public StackLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    public VBox build() {
        VBox container = new VBox(spacing);
        styleClasses.forEach(c -> container.getStyleClass().add(c));

        List<Node> row = new ArrayList<>();
        boolean headerDone = false;

        for (ComponentLayoutBuilder.Rendered rendered : ComponentLayoutBuilder.render(spec, audience, locked)) {
            if (rendered.component().kind() != BlockComponent.Kind.BODY) {
                row.add(rendered.node());
                continue;
            }
            // A body ends the row it follows. The body's own node carries its indentation and its accent bar,
            // because that chrome differs per block and is the block's to choose.
            if (!row.isEmpty() || !headerDone) {
                container.getChildren().add(rowNode(row, headerDone));
                headerDone = true;
                row = new ArrayList<>();
            }
            container.getChildren().add(rendered.node());
        }

        if (!row.isEmpty() || !headerDone) container.getChildren().add(rowNode(row, headerDone));
        return container;
    }

    /** One row: the header carries the delete cross and the header style classes, later rows are plain. */
    private Node rowNode(List<Node> nodes, boolean headerAlreadyBuilt) {
        WrappingSentencePane pane = ComponentLayoutBuilder.pane(nodes, rowSpacing, alignment);
        if (headerAlreadyBuilt) return pane;

        rowStyleClasses.forEach(c -> pane.getStyleClass().add(c));
        return BlockLayout.header()
                .withCustomNode(pane)
                .withDeleteButton(onDelete)
                .build();
    }
}
