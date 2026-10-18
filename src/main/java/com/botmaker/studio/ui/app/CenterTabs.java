package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.app.viewers.ResourceViewers;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The centre column's tabs: one per open Java file, and one per thing opened only to be looked at — a picture,
 * a data file, a library class (2026-09-27). Opening the same thing again brings its tab forward instead of
 * adding a second.
 *
 * <p><b>Java files in tabs (2026-10-13).</b> There is one canvas, and it is drawn into whichever Java file's
 * tab is selected: selecting a tab switches the editor to its file ({@code onSwitch}), and a switch made
 * elsewhere — Project Files, a usage, the assistant — opens or selects that file's tab ({@link #follow}). Every
 * tab closes, from its × or its menu (Close, Close Others, Close All, Close Tabs to the Left / Right), and with
 * none open the centre says how to open one. The editor keeps its open file meanwhile, so the tools that act
 * on "the open file" still have one; showing it again is a click on it in Project Files.
 *
 * <p><b>The canvas never moves (2026-10-18).</b> The tab pane is only the strip: no tab has content, and what
 * the selected tab shows is drawn in one area below it — the canvas, or the viewer in front of it. A node that
 * leaves the scene is restyled whole when it comes back, so moving the canvas into each tab cost a second CSS
 * pass over every block after each render (543 ms on {@code RenderBenchmarkTest}'s file): the canvas is only
 * hidden while a viewer is in front.
 */
final class CenterTabs {

    private static final String JAVA = "java:";

    private final TabPane tabs = new TabPane();
    private final VBox root;
    /** Below the strip: the canvas, the message for no tab, and the viewer of the selected tab when it has one. */
    private final StackPane area;
    private final Node canvas;
    /** Every tab, by what it shows — {@code java:<path>} for a Java file, a viewer's path or key otherwise. */
    private final Map<String, Tab> open = new LinkedHashMap<>();
    /** What each viewer's tab shows; a Java file's tab shows the canvas and has none. */
    private final Map<Tab, Node> views = new HashMap<>();
    /** The viewer drawn over the canvas now, or null. */
    private Node inFront;
    private Consumer<Path> onSwitch = file -> { };
    private Supplier<Path> active = () -> null;
    /** The Java file the canvas last showed in a tab; kept when that tab closes, so an edit does not reopen it. */
    private Path shown;
    /** While several tabs close or are restored at once, the selections they pass through switch nothing. */
    private boolean quiet;
    /** Tabs restored and waiting for the editor's first file, or null. */
    private Restored pending;

    /** Whether a file has been drawn in a tab yet; until then the canvas shows its loading message, tab or none. */
    private final BooleanProperty started = new SimpleBooleanProperty(false);

    /** What a tab shows from disk: a file, and whether the canvas draws it. */
    private record Shows(Path file, boolean java) { }

    /** A project's tabs reopened; {@code front} comes forward once its first file is drawn, or none with null. */
    private record Restored(Path front) { }

    CenterTabs(Node canvas) {
        this.canvas = canvas;
        tabs.getStyleClass().add("center-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setMaxHeight(Region.USE_PREF_SIZE);
        // A tab pane's minimum is 0: below the strip, a canvas or viewer asking for more height than the column
        // has squeezed it to nothing (2026-10-18). The strip keeps its height; the area gives way, clipped.
        tabs.setMinHeight(Region.USE_PREF_SIZE);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            // Drawn even while tabs close or are restored at once; only the switch waits for the one left in front.
            show(now);
            if (!quiet) selected(now);
        });
        Label none = new Label("No file open. Click one in Project Files to open it here.");
        none.getStyleClass().add("center-tabs-empty");
        none.visibleProperty().bind(Bindings.isEmpty(tabs.getTabs()).and(started));
        none.setMouseTransparent(true);
        area = new StackPane(canvas, none);
        area.setMinHeight(0);
        // Taller than the area, a centred child would lose its top to the clip.
        StackPane.setAlignment(canvas, Pos.TOP_LEFT);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(area.widthProperty());
        clip.heightProperty().bind(area.heightProperty());
        area.setClip(clip);
        VBox.setVgrow(area, Priority.ALWAYS);
        root = new VBox(tabs, area);
        show(null);
    }

    Node node() {
        return root;
    }

    /**
     * Where a selected Java tab sends the editor ({@code onSwitch}, given the file), and which file the editor
     * has open now ({@code active}).
     */
    void setEditor(Consumer<Path> onSwitch, Supplier<Path> active) {
        this.onSwitch = onSwitch == null ? file -> { } : onSwitch;
        this.active = active == null ? () -> null : active;
    }

    /** The open file's tab, opened when it has none, and selected. A no-op with no file open. */
    void showCanvas() {
        Path file = active.get();
        if (file != null) openJava(file);
    }

    /**
     * After the editor drew a file: when it is not the one last shown, it was switched to from elsewhere, so its
     * tab comes forward. An edit to the file shown changes nothing here — not even with its tab closed.
     */
    void follow() {
        Path file = active.get();
        if (pending != null) {
            // The project's first file is drawn: the tabs it was left with take over from it.
            Path front = pending.front();
            pending = null;
            if (front != null) {
                openJava(front);
            } else {
                shown = file == null ? null : normal(file);
                start();
            }
            return;
        }
        if (file != null && !normal(file).equals(shown)) openJava(file);
    }

    /** {@code file}'s tab, made when there is none, selected, and the canvas shown. */
    void openJava(Path file) {
        Path at = normal(file);
        Tab tab = open.get(JAVA + at);
        if (tab == null) tab = add(JAVA + at, at.getFileName().toString(), at.toString(), new Shows(at, true), null);
        if (tabs.getSelectionModel().getSelectedItem() == tab) selected(tab);
        else tabs.getSelectionModel().select(tab);
    }

    /** {@code file} in its viewer, in a tab of its own. */
    void openResource(Path file) {
        Path absolute = normal(file);
        String name = absolute.getFileName() == null ? absolute.toString() : absolute.getFileName().toString();
        Tab tab = open.get(absolute.toString());
        // Drawn afresh, but for a file being edited there: its unsaved text is the user's.
        if (tab == null) tab = add(absolute.toString(), name, absolute.toString(), new Shows(absolute, false),
                ResourceViewers.view(absolute));
        else if (!ResourceViewers.editing(views.get(tab))) views.put(tab, ResourceViewers.view(absolute));
        bringForward(tab);
    }

    /**
     * The tab {@code key} names, brought forward with {@code content} drawn afresh — a library class landed on
     * the member just asked for — or made first when there is none. The tooltip says where the thing is, since
     * two tabs can share a name.
     */
    void open(String key, String title, String where, Supplier<Node> content) {
        Tab tab = open.get(key);
        if (tab != null) views.put(tab, content.get());
        else tab = add(key, title, where, null, content.get());
        bringForward(tab);
    }

    /**
     * Closes the tabs of files that are gone — renamed or deleted. When the editor's own file is among them, the
     * rename or delete switches it next, and that file's tab comes forward then ({@link #follow}); nothing is
     * switched to on the way.
     */
    void closeMissing() {
        List<Tab> gone = tabs.getTabs().stream()
                .filter(tab -> tab.getUserData() instanceof Shows shows && !Files.exists(shows.file()))
                .toList();
        Path now = active.get();
        closeAll(gone, now == null || Files.exists(now));
    }

    /** The Java files open in tabs, in order. */
    List<Path> javaFiles() {
        return tabs.getTabs().stream().map(CenterTabs::javaFile).filter(Objects::nonNull).toList();
    }

    /** The Java file the canvas shows, or last showed while a viewer is in front; null when its tab is closed. */
    Path shownJavaFile() {
        return shown != null && open.containsKey(JAVA + shown) ? shown : null;
    }

    /**
     * Opens {@code files}' tabs as a project left them, before its first file is drawn; when it is
     * ({@link #follow}), {@code selected} comes forward. A project that never saved any ({@code null}), or whose
     * files are all gone, gets the first file's tab, as {@link #follow} gives any file; an empty list stays
     * empty: the user closed them all.
     */
    void restore(List<Path> files, Path selected) {
        if (files == null) return;
        quiet = true;
        try {
            for (Path file : files) {
                Path at = normal(file);
                if (Files.isRegularFile(at) && !open.containsKey(JAVA + at)) {
                    add(JAVA + at, at.getFileName().toString(), at.toString(), new Shows(at, true), null);
                }
            }
        } finally {
            quiet = false;
        }
        if (!files.isEmpty() && javaFiles().isEmpty()) return;
        pending = new Restored(selected != null && open.containsKey(JAVA + normal(selected)) ? normal(selected)
                : javaFiles().stream().findFirst().orElse(null));
    }

    /** The file restored tabs will bring forward, for the project's open to draw first; null when none. */
    Path restoredFront() {
        return pending == null ? null : pending.front();
    }

    /** The titles of the open tabs; for a test. */
    List<String> titles() {
        return tabs.getTabs().stream().map(Tab::getText).toList();
    }

    // ------------------------------------------------------------------

    private Tab add(String key, String title, String where, Shows shows, Node view) {
        Tab tab = new Tab(title);
        tab.setTooltip(new Tooltip(where));
        tab.setUserData(shows);
        if (view != null) views.put(tab, view);
        tab.setOnClosed(e -> {
            open.remove(key, tab);
            views.remove(tab);
        });
        tab.setContextMenu(menu(tab));
        open.put(key, tab);
        tabs.getTabs().add(tab);
        return tab;
    }

    /** {@code tab} selected, and drawn again when it already was: its viewer may be a new one. */
    private void bringForward(Tab tab) {
        tabs.getSelectionModel().select(tab);
        show(tab);
    }

    /**
     * Draws what {@code tab} shows below the strip: its viewer over the canvas, or the canvas for a Java file's.
     * With no tab, the canvas shows only until the first file is drawn, with its loading message.
     */
    private void show(Tab tab) {
        Node view = tab == null ? null : views.get(tab);
        if (view != inFront) {
            if (inFront != null) area.getChildren().remove(inFront);
            if (view != null) {
                StackPane.setAlignment(view, Pos.TOP_LEFT);
                area.getChildren().add(view);
            }
            inFront = view;
        }
        boolean canvasShows = view == null && (javaFile(tab) != null || tab == null && !started.get());
        // Hidden and out of layout, but never out of the scene: what leaves the scene is restyled whole on return.
        canvas.setVisible(canvasShows);
        canvas.setManaged(canvasShows);
    }

    /** A Java tab came forward: the editor switches to its file. */
    private void selected(Tab tab) {
        Path file = javaFile(tab);
        if (file == null) return;
        start();
        shown = file;
        Path now = active.get();
        if (now == null || !normal(now).equals(file)) onSwitch.accept(file);
    }

    /** The first file is drawn: from now on the canvas shows only for a Java file's tab. */
    private void start() {
        if (started.get()) return;
        started.set(true);
        show(tabs.getSelectionModel().getSelectedItem());
    }

    private ContextMenu menu(Tab tab) {
        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> closeAll(List.of(tab), true));
        MenuItem others = new MenuItem("Close Others");
        others.setOnAction(e -> closeAll(tabs.getTabs().stream().filter(t -> t != tab).toList(), true));
        MenuItem all = new MenuItem("Close All");
        all.setOnAction(e -> closeAll(List.copyOf(tabs.getTabs()), true));
        MenuItem left = new MenuItem("Close Tabs to the Left");
        left.setOnAction(e -> closeAll(List.copyOf(tabs.getTabs().subList(0, tabs.getTabs().indexOf(tab))), true));
        MenuItem right = new MenuItem("Close Tabs to the Right");
        right.setOnAction(e -> {
            int at = tabs.getTabs().indexOf(tab);
            closeAll(List.copyOf(tabs.getTabs().subList(at + 1, tabs.getTabs().size())), true);
        });
        ContextMenu menu = new ContextMenu(close, new SeparatorMenuItem(), others, all, left, right);
        menu.setOnShowing(e -> {
            int at = tabs.getTabs().indexOf(tab);
            others.setDisable(tabs.getTabs().size() < 2);
            left.setDisable(at <= 0);
            right.setDisable(at < 0 || at >= tabs.getTabs().size() - 1);
        });
        return menu;
    }

    /**
     * Closes {@code toClose} at once: the tabs the selection passes through on the way switch nothing, and the
     * one left in front, if it is a Java file's, is switched to once — unless {@code switchFront} is false,
     * when the editor is about to switch by itself.
     */
    private void closeAll(List<Tab> toClose, boolean switchFront) {
        if (toClose.isEmpty()) return;
        List<Tab> gone = new ArrayList<>(toClose);
        quiet = true;
        try {
            tabs.getTabs().removeAll(gone);
            for (Tab tab : gone) {
                if (tab.getOnClosed() != null) tab.getOnClosed().handle(null);
            }
        } finally {
            quiet = false;
        }
        Tab front = tabs.getSelectionModel().getSelectedItem();
        if (front != null && switchFront) selected(front);
    }

    private static Path javaFile(Tab tab) {
        return tab != null && tab.getUserData() instanceof Shows shows && shows.java() ? shows.file() : null;
    }

    private static Path normal(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
