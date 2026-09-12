package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.ActionContext;
import com.botmaker.plugin.api.ToolbarItem;
import com.botmaker.studio.ui.app.ToolbarItems;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;

import java.util.List;

/**
 * The HUD's plugin row: every {@code ToolbarGroup.OVERLAY} item, drawn by the host.
 *
 * <p>A {@link FlowPane} rather than an {@code HBox} because the panel is ~340px wide and the number of items
 * is whatever happens to be installed. Wrapping is the only honest answer at that width — an overflow menu
 * would cost a button of its own out of the same 340px, and a clipped row is a plugin silently absent, which
 * is the failure the whole toolbar merge is written to avoid.
 *
 * <p>Nothing here knows what an item does, and nothing here draws one. {@link ToolbarItems} builds the
 * button, so the style class, the tooltip, the icon box and the guard around a plugin action that throws are
 * the bar's and not a second copy of them.
 */
final class OverlayItemRow {

    private OverlayItemRow() {}

    /**
     * The row, or {@code null} when no plugin contributed anything.
     *
     * <p>{@code null} rather than an empty pane, and that is the ordinary case: with no project open no
     * plugin is loaded at all. The HUD stacks its rows, so an empty one would cost the tree real pixels for
     * something that says nothing.
     */
    static Node build(List<ToolbarItem> items, ActionContext ctx) {
        if (items == null || items.isEmpty()) return null;

        FlowPane row = new FlowPane(4, 4);
        row.setPadding(new Insets(2, 0, 0, 0));
        for (ToolbarItem item : items) {
            Button button = ToolbarItems.button(item, ctx);
            // The HUD's own controls are 11px; a bar-sized button here would make the row the tallest thing
            // on a panel whose whole point is to leave the game visible.
            button.setStyle("-fx-font-size: 11px;");
            row.getChildren().add(button);
        }
        return row;
    }
}
