package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.overlay.OverlayTool;
import com.botmaker.studio.plugin.PluginHost;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The panel's tool tabs: each plugin's {@link OverlayTool} panes first, then <b>Actions</b> (the
 * {@code ToolbarGroup.OVERLAY} items) and <b>Blocks</b> (the palette).
 *
 * <p>A tool's pane is built once per opening of the panel, as the contract says; one that throws costs only
 * its own tab, which says so. Actions is rebuilt on {@link #refreshActions}, since a code update can change
 * what a plugin offers.
 */
final class ToolTabs {

    private final TabPane tabs = new TabPane();
    private final Tab actions = new Tab("Actions");
    private final Supplier<Node> actionsRow;

    /**
     * @param contexts   the context each plugin's tools are built with, by plugin id
     * @param actionsRow the Actions tab's content, rebuilt on each refresh; null when no plugin offers one
     * @param blocks     the Blocks tab's content
     */
    ToolTabs(Function<String, ToolContext> contexts, Supplier<Node> actionsRow, Node blocks) {
        this.actionsRow = actionsRow;
        tabs.getStyleClass().add("overlay-tools");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        List<Tab> all = new ArrayList<>();
        for (PluginHost.OwnedOverlay owned : PluginHost.overlayParts()) {
            for (OverlayTool tool : owned.part().tools()) {
                all.add(new Tab(tool.label(), scroll(pane(owned, tool, contexts.apply(owned.pluginId())))));
            }
        }
        refreshActions();
        all.add(actions);
        all.add(new Tab("Blocks", scroll(blocks)));
        tabs.getTabs().setAll(all);
        tabs.setPrefHeight(230);
        tabs.setMinHeight(140);
    }

    TabPane node() {
        return tabs;
    }

    /** Rebuilds the Actions tab from what the plugins offer now. */
    void refreshActions() {
        Node row = actionsRow.get();
        actions.setContent(scroll(row != null ? row
                : OverlayStyles.dimLabel("No plugin of this project adds an action here.")));
    }

    private static Node pane(PluginHost.OwnedOverlay owned, OverlayTool tool, ToolContext context) {
        try {
            Node pane = tool.pane().create(context);
            if (pane != null) return pane;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Warning: " + owned.pluginId() + "'s overlay tool '" + tool.id()
                    + "' could not be drawn: " + e);
        }
        Label broken = OverlayStyles.dimLabel(owned.pluginName() + " could not draw this tool.");
        broken.setWrapText(true);
        return broken;
    }

    private static ScrollPane scroll(Node content) {
        VBox padded = new VBox(content);
        padded.setStyle("-fx-padding: 8;");
        ScrollPane scroll = new ScrollPane(padded);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("overlay-script");
        return scroll;
    }
}
