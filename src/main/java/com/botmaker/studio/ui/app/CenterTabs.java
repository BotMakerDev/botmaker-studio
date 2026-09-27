package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.app.viewers.ResourceViewers;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The centre column's tabs (2026-09-27): the canvas, always first and never closed, and beside it one closable
 * tab per thing opened only to be looked at — a picture, a data file, a library class. Opening the same thing
 * again brings its tab forward instead of adding a second.
 *
 * <p>With only the canvas open the tab strip is hidden ({@code .center-tabs--single}), so a project that never
 * opens anything else looks exactly as it did before there were tabs.
 */
final class CenterTabs {

    private static final String SINGLE = "center-tabs--single";

    private final TabPane tabs = new TabPane();
    private final Tab canvas;
    /** The viewer tabs, by what they show — a file's absolute path, or a library class's key. */
    private final Map<String, Tab> open = new LinkedHashMap<>();

    CenterTabs(Node canvasNode) {
        canvas = new Tab("Canvas", canvasNode);
        canvas.setClosable(false);
        tabs.getTabs().add(canvas);
        tabs.getStyleClass().add("center-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        refreshStrip();
    }

    Node node() {
        return tabs;
    }

    /** Names the canvas tab after the file it shows. */
    void setCanvasTitle(String title) {
        canvas.setText(title == null || title.isBlank() ? "Canvas" : title);
    }

    void showCanvas() {
        tabs.getSelectionModel().select(canvas);
    }

    /** {@code file} in its viewer, in a tab of its own. */
    void openResource(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        String name = absolute.getFileName() == null ? absolute.toString() : absolute.getFileName().toString();
        open(absolute.toString(), name, absolute.toString(), () -> ResourceViewers.view(absolute));
    }

    /**
     * The tab {@code key} names, brought forward with {@code content} drawn afresh — a file re-read, so it shows
     * what is on disk now; a library class landed on the member just asked for — or made first when there is
     * none. The tooltip says where the thing is, since two tabs can share a name.
     */
    void open(String key, String title, String where, Supplier<Node> content) {
        Tab tab = open.get(key);
        if (tab != null) {
            tab.setContent(content.get());
        } else {
            tab = new Tab(title, content.get());
            tab.setTooltip(new Tooltip(where));
            Tab made = tab;
            tab.setOnClosed(e -> {
                open.remove(key, made);
                refreshStrip();
            });
            open.put(key, tab);
            tabs.getTabs().add(tab);
            refreshStrip();
        }
        tabs.getSelectionModel().select(tab);
    }

    /** The titles of the open tabs, the canvas first; for a test. */
    List<String> titles() {
        return tabs.getTabs().stream().map(Tab::getText).toList();
    }

    private void refreshStrip() {
        tabs.getStyleClass().remove(SINGLE);
        if (tabs.getTabs().size() == 1) tabs.getStyleClass().add(SINGLE);
    }
}
