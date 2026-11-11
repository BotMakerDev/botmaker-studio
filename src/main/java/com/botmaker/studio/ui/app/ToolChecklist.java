package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.app.ToolCalls.Sort;
import com.botmaker.studio.ui.app.ToolCalls.Tally;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The Calls pane's tool checklist (spec {@code 2026-11-11-assistant-window-design.md}): every MCP tool called this
 * session by its own name, each with its plugin, its count and a bar of that count against the most called, ● while
 * one runs, and <em>Only</em>; All, None, a search over the names and the sort. By plugin, each plugin's box ticks all
 * its tools. It holds no filter: each click hands the window a change to its {@link CallFilter}.
 */
final class ToolChecklist {

    private static final double BAR_WIDTH = 60;

    private final Consumer<UnaryOperator<CallFilter>> change;
    private final VBox rows = new VBox(1);
    private final TextField search = new TextField();
    private final ComboBox<Sort> sort = new ComboBox<>();
    private final VBox node;
    private List<Tally> tallies = List.of();
    private CallFilter filter = CallFilter.ALL;
    /** What was last drawn: a told step changes no count, and draws nothing again. */
    private Object drawn;

    /** {@code change} applies a click to the window's filter; {@code sorted} asks for the list again in the new order. */
    ToolChecklist(Consumer<UnaryOperator<CallFilter>> change, Runnable sorted) {
        this.change = change;
        search.setPromptText("Find a tool");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((o, was, now) -> redraw());
        sort.getItems().setAll(Sort.values());
        sort.setValue(Sort.MOST_CALLED);
        sort.setButtonCell(new SortCell());
        sort.setCellFactory(list -> new SortCell());
        sort.valueProperty().addListener((o, was, now) -> sorted.run());
        Button all = new Button("All");
        all.setOnAction(e -> change.accept(CallFilter::allTicked));
        Button none = new Button("None");
        none.setOnAction(e -> change.accept(f -> f.noneTicked(names())));
        HBox top = new HBox(6, search, sort, all, none);
        top.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(150);
        scroll.setMaxHeight(220);
        rows.setPadding(new Insets(2, 4, 2, 4));
        node = new VBox(4, top, scroll);
    }

    Node node() {
        return node;
    }

    Sort sort() {
        return sort.getValue();
    }

    /** Draws {@code tallies}, in the order given, ticked as {@code filter} says. */
    void show(List<Tally> tallies, CallFilter filter) {
        this.tallies = tallies;
        this.filter = filter;
        redraw();
    }

    private List<String> names() {
        return tallies.stream().map(Tally::tool).toList();
    }

    private void redraw() {
        String words = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        Object now = List.of(tallies, filter.unticked(), words, sort());
        if (now.equals(drawn)) return;
        drawn = now;
        List<Tally> shown = tallies.stream().filter(t -> t.tool().toLowerCase(Locale.ROOT).contains(words)).toList();
        int most = tallies.stream().mapToInt(t -> sort() == Sort.LAST_5_MIN ? t.recent() : t.count()).max().orElse(1);
        List<Node> out = new ArrayList<>();
        if (tallies.isEmpty()) out.add(new Label("No tool called yet."));
        else if (shown.isEmpty()) out.add(new Label("No tool called is named so."));
        if (sort() == Sort.BY_PLUGIN) {
            Map<String, List<Tally>> byPlugin = new LinkedHashMap<>();
            for (Tally t : shown) byPlugin.computeIfAbsent(t.plugin(), p -> new ArrayList<>()).add(t);
            byPlugin.forEach((plugin, tools) -> {
                out.add(pluginBox(plugin, tools));
                for (Tally t : tools) out.add(row(t, most, 18));
            });
        } else {
            for (Tally t : shown) out.add(row(t, most, 0));
        }
        rows.getChildren().setAll(out);
    }

    private Node pluginBox(String plugin, List<Tally> tools) {
        List<String> names = tools.stream().map(Tally::tool).toList();
        long ticked = names.stream().filter(filter::ticks).count();
        CheckBox box = new CheckBox(plugin.isEmpty() ? "BotMaker core" : plugin);
        box.setStyle("-fx-font-weight: bold;");
        box.setAllowIndeterminate(false);
        box.setSelected(ticked == names.size());
        box.setIndeterminate(ticked > 0 && ticked < names.size());
        box.setOnAction(e -> change.accept(f -> f.ticking(names, ticked < names.size())));
        return box;
    }

    private Node row(Tally tally, int most, double indent) {
        CheckBox box = new CheckBox(tally.tool());
        box.setSelected(filter.ticks(tally.tool()));
        box.setOnAction(e -> change.accept(f -> f.toggled(tally.tool())));
        box.setMinWidth(Region.USE_PREF_SIZE);
        Label running = new Label(tally.running() ? "●" : "");
        running.setStyle("-fx-text-fill: #34c759;");
        if (tally.running()) running.setTooltip(new Tooltip("Running now"));
        Label plugin = new Label(tally.plugin().isEmpty() ? "core" : tally.plugin());
        plugin.setStyle("-fx-opacity: 0.65;");
        plugin.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        int value = sort() == Sort.LAST_5_MIN ? tally.recent() : tally.count();
        Label count = new Label(sort() == Sort.LAST_5_MIN ? tally.recent() + " / " + tally.count()
                : String.valueOf(tally.count()));
        count.setTooltip(new Tooltip(tally.count() + " calls, " + tally.recent() + " in the last 5 min"));
        count.setMinWidth(Region.USE_PREF_SIZE);
        Hyperlink only = new Hyperlink("Only");
        only.setOnAction(e -> change.accept(f -> f.only(tally.tool(), names())));
        HBox row = new HBox(6, box, running, plugin, spacer, bar(value, most), count, only);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 0, indent));
        return row;
    }

    private static Node bar(int value, int most) {
        Region filled = new Region();
        filled.setStyle("-fx-background-color: -fx-accent; -fx-background-radius: 2;");
        double width = most <= 0 ? 0 : BAR_WIDTH * value / most;
        filled.setMinSize(width, 6);
        filled.setMaxSize(width, 6);
        Region track = new Region();
        track.setStyle("-fx-background-color: derive(-fx-base, -12%); -fx-background-radius: 2;");
        track.setMinSize(BAR_WIDTH, 6);
        track.setMaxSize(BAR_WIDTH, 6);
        StackPane bar = new StackPane(track, filled);
        StackPane.setAlignment(filled, Pos.CENTER_LEFT);
        bar.setMinWidth(BAR_WIDTH);
        bar.setMaxHeight(6);
        return bar;
    }

    private static final class SortCell extends ListCell<Sort> {
        @Override
        protected void updateItem(Sort item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.displayName());
        }
    }
}
