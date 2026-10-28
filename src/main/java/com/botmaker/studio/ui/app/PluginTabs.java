package com.botmaker.studio.ui.app;

import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.plugin.PluginHost.PluginBar;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The toolbar's second row (2026-10-28): one tab per plugin that has bar buttons, then the selected plugin's
 * buttons.
 *
 * <p>The tabs read most recent first, and the first is the selected one, so the selection is the head of the
 * project's {@code toolbarTabs} list and nothing else has to remember it. The tabs that do not fit go into a
 * {@code » N more} button whose list filters as you type; picking a plugin there, like clicking a tab, moves
 * it to the front. A plugin with no bar button has no tab: {@link PluginBar} only exists for one that has.
 *
 * <p>The ordering, the pick and the width maths are static and toolkit-free, for {@code PluginTabsTest}; the
 * width maths is {@link ToolbarPacking}'s, one row deep.
 */
final class PluginTabs extends Region {

    static final double HGAP = 5;

    /**
     * How much of the row the tabs may take before they give way to the buttons. The buttons are what the
     * row is for; the tabs only say whose they are.
     */
    private static final double TABS_SHARE = 0.45;

    private final List<PluginBar> bars;
    private final List<Button> tabs = new ArrayList<>();
    private final Button more = new Button();
    private final Node buttons;
    private final Consumer<PluginCoordinate> onPick;
    private int shown;

    /**
     * @param bars    the tabs in the order to draw them ({@link #ordered}); the first is selected
     * @param buttons the selected plugin's buttons, built by the toolbar
     * @param onPick  what a click on a tab, or a pick from the list, does with that plugin
     */
    PluginTabs(List<PluginBar> bars, Node buttons, Consumer<PluginCoordinate> onPick) {
        this.bars = List.copyOf(bars);
        this.buttons = buttons;
        this.onPick = onPick;
        getStyleClass().add("plugin-tabs");
        for (int i = 0; i < this.bars.size(); i++) {
            PluginBar bar = this.bars.get(i);
            Button tab = new Button(bar.name(), PluginRowParts.icon(Optional.ofNullable(bar.jar())));
            tab.getStyleClass().add("plugin-tab");
            if (i == 0) tab.getStyleClass().add("plugin-tab-selected");
            tab.setTooltip(new Tooltip(bar.name() + " — " + bar.plugin()));
            tab.setMinWidth(Region.USE_PREF_SIZE);
            tab.setOnAction(e -> onPick.accept(bar.plugin()));
            tabs.add(tab);
        }
        more.getStyleClass().addAll("plugin-tab", "plugin-tab-more");
        more.setTooltip(new Tooltip("The other plugins — type to find one"));
        more.setMinWidth(Region.USE_PREF_SIZE);
        more.setOnAction(e -> showRest());
        more.setVisible(false);
        getChildren().addAll(tabs);
        getChildren().addAll(more, buttons);
    }

    // ── what is drawn: pure, for the test ──────────────────────────────────────────────────────────────

    /**
     * The tabs in drawing order: {@code recent}'s plugins first, in its order, then every other in
     * {@code bars}' own order (by name). A recent coordinate no bar has is skipped, which is how a removed
     * plugin drops out.
     */
    static List<PluginBar> ordered(List<PluginBar> bars, List<String> recent) {
        List<PluginBar> out = new ArrayList<>();
        for (String coordinate : recent) {
            for (PluginBar bar : bars) {
                if (bar.plugin().toString().equals(coordinate) && !out.contains(bar)) out.add(bar);
            }
        }
        for (PluginBar bar : bars) if (!out.contains(bar)) out.add(bar);
        return List.copyOf(out);
    }

    /**
     * The project's tab list after {@code picked} is chosen: it first, then the rest of the drawing order.
     * Writing the whole order, not only the pick, is what drops a coordinate no plugin has any more.
     */
    static List<String> picked(List<PluginBar> ordered, PluginCoordinate picked) {
        Set<String> out = new LinkedHashSet<>();
        out.add(picked.toString());
        for (PluginBar bar : ordered) out.add(bar.plugin().toString());
        return List.copyOf(out);
    }

    /** How many of the tabs, {@code widths} wide, fit in {@code width} with the {@code »} button after them. */
    static int visibleCount(double[] widths, double width, double moreWidth) {
        ToolbarPacking.Packing packing = ToolbarPacking.pack(widths, width, HGAP, 1, moreWidth);
        // The selected tab is always drawn, even in a row too narrow for it.
        return packing.rows().isEmpty() ? Math.min(1, widths.length) : Math.max(1, packing.rows().getFirst().size());
    }

    /** {@code bars} whose name or coordinate holds {@code filter}, ignoring case; all of them for a blank one. */
    static List<PluginBar> matching(List<PluginBar> bars, String filter) {
        if (filter == null || filter.isBlank()) return bars;
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        return bars.stream().filter(bar -> bar.name().toLowerCase(Locale.ROOT).contains(needle)
                || bar.plugin().toString().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    /** "» 3 more". */
    static String moreLabel(int hidden) {
        return "» " + hidden + " more";
    }

    // ── layout ─────────────────────────────────────────────────────────────────────────────────────────

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double width = Math.max(0, getWidth() - in.getLeft() - in.getRight());
        int count = fitting(width);
        shown = count;
        // Set on every pass: the same text changes nothing, and a new count asks for one more pass, which
        // measures the » button at its real width.
        more.setText(moreLabel(tabs.size() - count));
        more.setVisible(count < tabs.size());
        double height = getHeight() - in.getTop() - in.getBottom();
        double x = in.getLeft();
        for (int i = 0; i < tabs.size(); i++) {
            Button tab = tabs.get(i);
            tab.setVisible(i < count);
            if (i >= count) continue;
            x = place(tab, x, in.getTop(), height);
        }
        if (more.isVisible()) x = place(more, x, in.getTop(), height);
        double gap = tabs.isEmpty() ? 0 : HGAP * 3;
        double rest = Math.max(0, in.getLeft() + width - x - gap);
        buttons.resizeRelocate(x + gap, in.getTop() + (height - buttons.prefHeight(rest)) / 2, rest,
                buttons.prefHeight(rest));
    }

    private double place(Node node, double x, double top, double height) {
        double w = node.prefWidth(-1);
        double h = node.prefHeight(-1);
        node.resizeRelocate(x, top + (height - h) / 2, w, h);
        return x + w + HGAP;
    }

    /** How many tabs fit in the tabs' share of {@code width}. */
    private int fitting(double width) {
        double[] widths = tabs.stream().mapToDouble(tab -> tab.prefWidth(-1)).toArray();
        // The » button as last laid out: its count changes by a digit at most, and setting its text here
        // would ask for another layout pass from inside this one.
        return visibleCount(widths, tabsWidth(widths, width), more.prefWidth(-1));
    }

    /** The tabs' share: all of what they ask for when it is under {@link #TABS_SHARE} of the row. */
    private static double tabsWidth(double[] widths, double width) {
        double all = 0;
        for (double w : widths) all += w + HGAP;
        return Math.min(all, width * TABS_SHARE);
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        double w = 0;
        for (Button tab : tabs) w += tab.prefWidth(-1) + HGAP;
        return in.getLeft() + w + HGAP * 3 + buttons.prefWidth(-1) + in.getRight();
    }

    /** Zero, for {@link OverflowBar#computeMinWidth}'s reason: the toolbar never stops the window shrinking. */
    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double w = width >= 0 ? width : getWidth();
        double rest = Math.max(0, w - in.getLeft() - in.getRight() - w * TABS_SHARE);
        double h = more.prefHeight(-1);
        for (Button tab : tabs) h = Math.max(h, tab.prefHeight(-1));
        return in.getTop() + Math.max(h, buttons.prefHeight(rest)) + in.getBottom();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    // ── the » list ─────────────────────────────────────────────────────────────────────────────────────

    /** The tabs that did not fit, under the {@code »} button, narrowed by what is typed. */
    private void showRest() {
        List<PluginBar> rest = bars.subList(Math.max(0, shown), bars.size());
        TextField field = new TextField();
        field.setPromptText("Find a plugin");
        ListView<PluginBar> list = new ListView<>();
        list.setPrefSize(320, Math.min(320, 34 + rest.size() * 30.0));
        list.getStyleClass().add("nav-popup-list");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(PluginBar bar, boolean empty) {
                super.updateItem(bar, empty);
                if (empty || bar == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label coordinate = new Label(bar.plugin().toString());
                coordinate.getStyleClass().add("plugin-tab-coordinate");
                HBox cell = new HBox(6, PluginRowParts.icon(Optional.ofNullable(bar.jar())), new Label(bar.name()),
                        coordinate);
                cell.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(cell);
            }
        });
        list.getItems().setAll(rest);
        list.getSelectionModel().selectFirst();

        VBox box = new VBox(6, field, list);
        box.getStyleClass().add("nav-popup");
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(box);
        ThemedWindows.addStylesheet(popup.getScene());
        ThemedWindows.applyThemeClass(box);

        Runnable pick = () -> {
            PluginBar chosen = list.getSelectionModel().getSelectedItem();
            if (chosen == null) return;
            popup.hide();
            onPick.accept(chosen.plugin());
        };
        field.textProperty().addListener((o, was, now) -> {
            list.getItems().setAll(matching(rest, now));
            list.getSelectionModel().selectFirst();
        });
        field.setOnKeyPressed(e -> {
            int at = list.getSelectionModel().getSelectedIndex();
            if (e.getCode() == KeyCode.DOWN) {
                list.getSelectionModel().select(Math.min(at + 1, list.getItems().size() - 1));
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                list.getSelectionModel().select(Math.max(at - 1, 0));
                e.consume();
            }
        });
        field.setOnAction(e -> pick.run());
        // Only a click on a row picks: the scrollbar and the blank space under the rows are list too.
        list.setOnMouseClicked(e -> {
            for (Node n = e.getPickResult().getIntersectedNode(); n != null && n != list; n = n.getParent()) {
                if (n instanceof ListCell<?> cell && !cell.isEmpty()) {
                    pick.run();
                    return;
                }
            }
        });

        Bounds at = more.localToScreen(more.getBoundsInLocal());
        popup.show(more, at.getMinX(), at.getMaxY() + 2);
        field.requestFocus();
    }
}
