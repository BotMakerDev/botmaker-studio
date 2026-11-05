package com.botmaker.studio.ui.app.reports;

import com.botmaker.cli.reports.IssueReports;
import com.botmaker.cli.reports.Reports;
import com.botmaker.cli.reports.RunReports;
import com.botmaker.plugin.api.run.ReportView;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.api.Dimension;
import com.botmaker.runlog.api.Level;
import com.botmaker.studio.assist.PastRuns;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.studio.runtime.RunTelemetry;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Reports tab (run reports spec §5): a bot's problems, from its runs on this computer or from the issues its
 * runners sent, grouped by one of the dimensions the reports name (Studio knows none) — a bar per value stacked by
 * level, a bar per day stacked by value, lines over the days, a value × day heatmap, a donut of shares or a sortable
 * table ({@link Kind}). A loaded plugin's {@link ReportView}, picked by title, sets all three over its own line
 * values. A bar, point, cell, slice or row lists its problems; a problem opens its bot line or its issues.
 *
 * <p>Issue titles and values are written by runners: shown as text, never acted on.
 */
public final class ReportsPanel {

    /** Bars drawn at most, per chart. */
    static final int MOST = 15;
    /** Values stacked at most per day; the rest are {@link ReportCharts#OTHER}. */
    static final int MOST_PER_DAY = 6;
    /** Days a heatmap draws at most, the latest. */
    static final int HEAT_DAYS = 31;

    /** Where the reports are read from. */
    enum Source {
        RUNS("Runs on this computer"), ISSUES("Issues on GitHub");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    /** How the chart is drawn. */
    enum Kind {
        BARS("Bars"), BARS_BY_DAY("Bars by day"), LINES("Lines"), HEATMAP("Heatmap"), DONUT("Donut"),
        TABLE("Table");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    /** Which levels are counted. */
    enum Only {
        ALL("All problems", null), WARNINGS("Warnings", Level.WARN), ERRORS("Errors", Level.ERROR);

        private final String displayName;
        private final Level level;

        Only(String displayName, Level level) {
            this.displayName = displayName;
            this.level = level;
        }

        String displayName() {
            return displayName;
        }
    }

    /** What the View picker offers: today's any dimension, or one of a plugin's views. */
    record View(ReportView view) {
        static final View ANY = new View(null);

        String title() {
            return view == null ? "Any dimension" : view.title();
        }
    }

    private final Path sourceRoot;
    private final BiConsumer<Path, Integer> onReveal;
    private final Optional<ReportTarget> target;
    private final Supplier<Reports> runs;
    private final Function<ReportTarget, Reports> issues;
    private final Supplier<List<ReportView>> views;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    /** The newest load: an older one that ends later is dropped. */
    private final AtomicInteger loads = new AtomicInteger();

    private final BorderPane root = new BorderPane();
    private final ComboBox<Source> source = new ComboBox<>();
    private final ComboBox<View> view = new ComboBox<>();
    private final ComboBox<Dimension> groupBy = new ComboBox<>();
    private final ComboBox<Kind> kind = new ComboBox<>();
    private final ComboBox<Only> only = new ComboBox<>();
    private final Label notes = new Label();
    private final StackPane chartHolder = new StackPane();
    private static final String PICK_HINT = "Click a value in the chart to list its problems.";
    private final Label picked = new Label(PICK_HINT);
    private final ListView<ReportCharts.Row> rows = new ListView<>();
    private final FlowPane links = new FlowPane(8, 4);

    private Reports shown;
    private String pickedValue;
    /** True while pickers are set in code: their actions draw nothing, the one who set them draws once. */
    private boolean settling;

    /** The Reports tab of the project in {@code project}; a problem's line opens through {@code onReveal}. */
    public ReportsPanel(EventBus eventBus, Path project, Path sourceRoot, BiConsumer<Path, Integer> onReveal) {
        this(sourceRoot, onReveal, ReportTarget.of(project),
                () -> RunReports.read(project.resolve(RunLog.FOLDER)),
                t -> new IssueReports(PastRuns.signedIn(), IssueReports.defaultCache()).read(t),
                PluginHost::reportViews);
        // A run's end adds its problems to this computer's.
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.BotRunEndedEvent.class, e -> {
            if (source.getValue() == Source.RUNS) load();
        }, true));
    }

    ReportsPanel(Path sourceRoot, BiConsumer<Path, Integer> onReveal, Optional<ReportTarget> target,
                 Supplier<Reports> runs, Function<ReportTarget, Reports> issues, Supplier<List<ReportView>> views) {
        this.sourceRoot = sourceRoot;
        this.onReveal = onReveal;
        this.target = target;
        this.runs = runs;
        this.issues = issues;
        this.views = views;
        build();
        load();
    }

    public Node node() {
        return root;
    }

    /** Shows this computer's runs, read again (the toolbar's Reports button). */
    public void showRuns() {
        if (source.getValue() == Source.RUNS) load();
        else source.setValue(Source.RUNS); // its action loads
    }

    public void dispose() {
        loads.incrementAndGet(); // a load still under way draws nothing
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
    }

    private void build() {
        source.getItems().add(Source.RUNS);
        target.ifPresent(t -> source.getItems().add(Source.ISSUES));
        source.setValue(Source.RUNS);
        source.setConverter(named(s -> s == Source.ISSUES ? "Issues on " + target.map(ReportTarget::toString)
                .orElse("GitHub") : s.displayName()));
        view.getItems().setAll(View.ANY);
        view.setValue(View.ANY);
        view.setConverter(named(View::title));
        groupBy.setConverter(named(Dimension::label));
        kind.getItems().setAll(Kind.values());
        kind.setValue(Kind.BARS);
        kind.setConverter(named(Kind::displayName));
        only.getItems().setAll(Only.values());
        only.setValue(Only.ALL);
        only.setConverter(named(Only::displayName));
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> load());

        source.setOnAction(e -> load());
        view.setOnAction(e -> applyView());
        groupBy.setOnAction(e -> redraw());
        kind.setOnAction(e -> redraw());
        only.setOnAction(e -> redraw());

        HBox bar = new HBox(8, new Label("From"), source, new Label("View"), view, new Label("Group by"), groupBy,
                new Label("Chart"), kind, only, refresh, notes);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        notes.setWrapText(true);
        HBox.setHgrow(notes, Priority.ALWAYS);
        notes.setMaxWidth(Double.MAX_VALUE);

        rows.setPlaceholder(new Label("No problem picked."));
        rows.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(ReportCharts.Row row, boolean empty) {
                super.updateItem(row, empty);
                setText(empty || row == null ? null : rowText(row));
            }
        });
        rows.getSelectionModel().selectedItemProperty().addListener((o, was, row) -> showLinks(row));
        rows.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) reveal(rows.getSelectionModel().getSelectedItem());
        });
        picked.setWrapText(true);
        VBox side = new VBox(6, picked, rows, links);
        side.setPadding(new Insets(4, 8, 8, 8));
        VBox.setVgrow(rows, Priority.ALWAYS);

        SplitPane split = new SplitPane(chartHolder, side);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.62);
        root.setTop(bar);
        root.setCenter(split);
    }

    /** Reads the picked source off the FX thread, then draws it. */
    private void load() {
        int load = loads.incrementAndGet();
        Source from = source.getValue();
        notes.setText(from == Source.ISSUES ? "Reading the issues…" : "Reading the runs…");
        Thread.ofVirtual().name("reports-load").start(() -> {
            Reports read;
            try {
                read = from == Source.ISSUES && target.isPresent() ? issues.apply(target.get()) : runs.get();
            } catch (RuntimeException | Error e) {
                // The chart no longer shows the source picked before: nothing rather than the wrong reports.
                Platform.runLater(() -> {
                    if (load != loads.get()) return;
                    shown = null;
                    redraw();
                    notes.setText("The reports could not be read: " + e.getMessage());
                });
                return;
            }
            Platform.runLater(() -> {
                if (load == loads.get()) show(read);
            });
        });
    }

    /** Draws {@code reports}, keeping the dimension picked when they name it too. */
    void show(Reports reports) {
        shown = reports;
        settling = true;
        String was = groupBy.getValue() == null ? null : groupBy.getValue().id();
        List<Dimension> dims = reports.report().dimensions();
        groupBy.getItems().setAll(dims);
        groupBy.setValue(dims.stream().filter(d -> d.id().equals(was)).findFirst()
                .or(() -> dims.stream().filter(d -> d.id().equals("botLine")).findFirst())
                .orElse(dims.isEmpty() ? null : dims.getFirst()));
        // The loaded plugins' views, read again each time: a plugin bound since adds its own.
        String title = view.getValue() == null ? View.ANY.title() : view.getValue().title();
        List<View> offered = new ArrayList<>(List.of(View.ANY));
        views.get().forEach(v -> offered.add(new View(v)));
        view.getItems().setAll(offered);
        view.setValue(offered.stream().filter(v -> v.title().equals(title)).findFirst().orElse(View.ANY));
        settling = false;
        List<String> said = new ArrayList<>(reports.notes());
        if (reports.report().occurrences().isEmpty()) said.add("No problems.");
        notes.setText(String.join(" ", said));
        applyView();
    }

    /**
     * Sets the pickers a plugin's view decides — its chart and dimension, its level — and locks them, or frees them
     * for any dimension; then draws.
     */
    private void applyView() {
        if (settling) return;
        ReportView picked = pluginView();
        groupBy.setDisable(picked != null);
        kind.setDisable(picked != null);
        only.setDisable(picked != null);
        if (picked != null) {
            settling = true;
            try {
                kind.setValue(Kind.valueOf(picked.chart().name()));
                only.setValue(Only.ALL);
                String id = Dimension.plugin(picked.dimension());
                groupBy.getItems().stream().filter(d -> d.id().equals(id)).findFirst().ifPresent(groupBy::setValue);
            } finally {
                settling = false;
            }
        }
        redraw();
    }

    /** The plugin's view picked, or null for any dimension. */
    private ReportView pluginView() {
        return view.getValue() == null ? null : view.getValue().view();
    }

    /** The dimension counted: the view's own, else the one grouped by; null when there is none. */
    private Dimension grouped() {
        ReportView picked = pluginView();
        if (picked == null) return groupBy.getValue();
        String id = Dimension.plugin(picked.dimension());
        return groupBy.getItems().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElse(new Dimension(id, picked.dimension()));
    }

    private void redraw() {
        if (settling) return;
        pick(null);
        Dimension dim = grouped();
        if (shown == null || dim == null) {
            chartHolder.getChildren().setAll(new Label("No problems to draw."));
            return;
        }
        ReportView picked = pluginView();
        List<String> missing = picked == null ? List.of() : ReportCharts.missing(shown.report(), picked);
        if (!missing.isEmpty()) {
            // A view names keys a plugin's lines carry: these reports have none of them, an older run or issue.
            Label none = new Label("“" + picked.title() + "” counts " + String.join(" and ", missing)
                    + ", which none of these reports carry.");
            none.setWrapText(true);
            chartHolder.getChildren().setAll(none);
            return;
        }
        Reports counted = counted();
        String id = dim.id();
        String unit = picked == null ? "problems" : picked.weight()
                .orElse(picked.lines() == ReportView.Lines.EVERY_LINE ? "lines" : "problems");
        Optional<String> against = picked == null ? Optional.empty() : picked.against().map(Dimension::plugin);
        chartHolder.getChildren().setAll(switch (kind.getValue() == null ? Kind.BARS : kind.getValue()) {
            case BARS -> totals(ReportCharts.totals(counted.report(), id, MOST));
            case BARS_BY_DAY -> byDay(ReportCharts.byDay(counted.report(), id, MOST_PER_DAY));
            case LINES -> lines(ReportCharts.lines(counted.report(), id, MOST_PER_DAY));
            case HEATMAP -> against.isPresent()
                    ? heatmap(ReportCharts.grid(counted.report(), id, against.get(), MOST), false)
                    : heatmap(ReportCharts.heatmap(counted.report(), id, MOST, HEAT_DAYS), true);
            case DONUT -> donut(ReportCharts.share(counted.report(), id, MOST_PER_DAY), unit);
            case TABLE -> table(ReportCharts.table(counted, id));
        });
    }

    /** What is counted: the plugin's view's lines, else the problems at the level picked. */
    private Reports counted() {
        ReportView picked = pluginView();
        if (picked != null) return ReportCharts.viewed(shown, picked);
        return shown.filtered(only.getValue() == null ? null : only.getValue().level, null);
    }

    /** Lists the problems of {@code value} of the dimension grouped by; null clears the list. */
    void pick(String value) {
        pickedValue = value;
        Dimension dim = grouped();
        if (value == null || dim == null || shown == null) {
            picked.setText(PICK_HINT);
            rows.getItems().clear();
            return;
        }
        picked.setText(dim.label() + ": " + value + " — double-click a problem to open its line.");
        rows.getItems().setAll(ReportCharts.rows(counted(), dim.id(), value));
        if (!rows.getItems().isEmpty()) rows.getSelectionModel().selectFirst();
    }

    String pickedValue() {
        return pickedValue;
    }

    List<ReportCharts.Row> rowsShown() {
        return List.copyOf(rows.getItems());
    }

    private Node totals(ReportCharts.Chart chart) {
        NumberAxis count = new NumberAxis();
        count.setLabel("Times met");
        CategoryAxis values = new CategoryAxis();
        List<String> topFirst = new ArrayList<>(chart.categories());
        Collections.reverse(topFirst);
        values.getCategories().setAll(topFirst);
        StackedBarChart<Number, String> bars = new StackedBarChart<>(count, values);
        bars.setAnimated(false);
        // A level keeps its colour whichever levels are drawn: the chart's palette slots, set per series.
        StringBuilder palette = new StringBuilder();
        for (int i = 0; i < chart.series().size(); i++) {
            palette.append("CHART_COLOR_").append(i + 1).append(": ")
                    .append(levelColour(chart.series().get(i).name())).append("; ");
        }
        bars.setStyle(palette.toString());
        for (ReportCharts.Series s : chart.series()) {
            XYChart.Series<Number, String> series = new XYChart.Series<>();
            series.setName(s.name());
            for (ReportCharts.Point p : s.points()) {
                XYChart.Data<Number, String> data = new XYChart.Data<>(p.count(), p.category());
                clickable(data.nodeProperty(), p.category(), s.name() + " · " + p.category() + ": " + p.count());
                series.getData().add(data);
            }
            bars.getData().add(series);
        }
        return bars;
    }

    /** Amber for a warning, red for an error, grey for any other level a newer log names. */
    private static String levelColour(String seriesName) {
        if (seriesName.equals(Level.WARN.displayName())) return "#e0a030";
        if (seriesName.equals(Level.ERROR.displayName())) return "#d64541";
        return "#8a8a8a";
    }

    private Node byDay(ReportCharts.Chart chart) {
        CategoryAxis days = new CategoryAxis();
        days.getCategories().setAll(chart.categories());
        NumberAxis count = new NumberAxis();
        count.setLabel("Times met");
        StackedBarChart<String, Number> bars = new StackedBarChart<>(days, count);
        bars.setAnimated(false);
        for (ReportCharts.Series s : chart.series()) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(s.name());
            String value = s.rest() ? null : s.name();
            for (ReportCharts.Point p : s.points()) {
                XYChart.Data<String, Number> data = new XYChart.Data<>(p.category(), p.count());
                clickable(data.nodeProperty(), value, s.name() + " · " + p.category() + ": " + p.count());
                series.getData().add(data);
            }
            bars.getData().add(series);
        }
        return bars;
    }

    /** A line per value over the days; a point lists its value's problems. */
    private Node lines(ReportCharts.Chart chart) {
        CategoryAxis days = new CategoryAxis();
        days.getCategories().setAll(chart.categories());
        NumberAxis count = new NumberAxis();
        count.setLabel("Times met");
        LineChart<String, Number> lines = new LineChart<>(days, count);
        lines.setAnimated(false);
        for (ReportCharts.Series s : chart.series()) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(s.name());
            String value = s.rest() ? null : s.name();
            for (ReportCharts.Point p : s.points()) {
                XYChart.Data<String, Number> data = new XYChart.Data<>(p.category(), p.count());
                clickable(data.nodeProperty(), value, s.name() + " · " + p.category() + ": " + p.count());
                series.getData().add(data);
            }
            lines.getData().add(series);
        }
        return lines;
    }

    /**
     * A row per value, a column per day ({@code overDays}) or per value of a view's second key, each cell shaded by
     * its count against the largest (JavaFX has no heatmap). A cell or a row's name lists its value's problems.
     */
    private Node heatmap(ReportCharts.Heat heat, boolean overDays) {
        GridPane grid = new GridPane(2, 2);
        grid.getStyleClass().add("reports-heatmap");
        grid.setPadding(new Insets(8));
        for (int d = 0; d < heat.days().size(); d++) {
            String column = heat.days().get(d);
            // MM-dd: the year is in the tooltip
            Label day = new Label(overDays && column.length() > 5 ? column.substring(5) : column);
            day.setMaxWidth(160);
            day.setTooltip(new Tooltip(column));
            grid.add(day, d + 1, 0);
            GridPane.setHalignment(day, HPos.CENTER);
        }
        int max = Math.max(1, heat.max());
        for (int v = 0; v < heat.values().size(); v++) {
            String value = heat.values().get(v);
            Label name = new Label(value);
            name.setMaxWidth(260);
            name.setTooltip(new Tooltip(value));
            pickOnClick(name, value);
            grid.add(name, 0, v + 1);
            for (int d = 0; d < heat.days().size(); d++) {
                int n = heat.counts().get(v).get(d);
                Label cell = new Label(n == 0 ? "" : String.valueOf(n));
                cell.setMinSize(44, 24);
                cell.setAlignment(Pos.CENTER);
                double share = (double) n / max;
                // One hue, from faint to full; the count is written in the cell, so colour is not the only cue.
                cell.setStyle(n == 0 ? "-fx-border-color: rgba(128,128,128,0.25);"
                        : String.format(Locale.ROOT, "-fx-background-color: rgba(214,69,65,%.2f);%s",
                        0.15 + 0.85 * share, share > 0.5 ? " -fx-text-fill: white;" : ""));
                cell.setTooltip(new Tooltip(value + " · " + heat.days().get(d) + ": " + n));
                if (n > 0) pickOnClick(cell, value);
                grid.add(cell, d + 1, v + 1);
            }
        }
        if (heat.values().isEmpty()) return new Label("No problems to draw.");
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToHeight(true);
        return scroll;
    }

    /** Each value's share of the {@code unit}, a slice each, with the total in the hole; a slice lists its problems. */
    private Node donut(List<ReportCharts.Slice> slices, String unit) {
        PieChart pie = new PieChart();
        pie.setAnimated(false);
        pie.setLegendVisible(false);
        pie.setStartAngle(90);
        int total = slices.stream().mapToInt(ReportCharts.Slice::count).sum();
        for (ReportCharts.Slice s : slices) {
            PieChart.Data data = new PieChart.Data(s.value(), s.count());
            pie.getData().add(data);
            String tip = s.value() + ": " + s.count() + String.format(Locale.ROOT, " (%.0f%%)", 100.0 * s.count() / total);
            Tooltip.install(data.getNode(), new Tooltip(tip));
            if (s.rest()) data.getNode().setStyle("-fx-pie-color: #8a8a8a;");
            else pickOnClick(data.getNode(), s.value());
        }
        // PieChart has no hole: a disc of the panel's background over its middle, sized off the pie.
        Region hole = new Region();
        hole.getStyleClass().add("reports-donut-hole");
        hole.setStyle("-fx-background-color: -fx-background; -fx-background-radius: 50%;");
        hole.setOnMouseClicked(MouseEvent::consume); // the hole is empty: a click there picks no slice
        Label sum = new Label(total + "\n" + unit);
        sum.setTextAlignment(TextAlignment.CENTER);
        sum.setMouseTransparent(true);
        StackPane donut = new StackPane(pie, hole, sum);
        hole.maxWidthProperty().bind(Bindings.min(donut.widthProperty(), donut.heightProperty()).multiply(0.24));
        hole.maxHeightProperty().bind(hole.maxWidthProperty());
        return donut;
    }

    /** Every value with its counts, first and last day seen and issues; any column sorts, a row lists its problems. */
    private Node table(List<ReportCharts.TableRow> all) {
        TableView<ReportCharts.TableRow> table = new TableView<>();
        table.getStyleClass().add("reports-table");
        table.getColumns().add(column("Value", r -> r.value()));
        table.getColumns().add(column("Warnings", ReportCharts.TableRow::warnings));
        table.getColumns().add(column("Errors", ReportCharts.TableRow::errors));
        table.getColumns().add(column("Total", ReportCharts.TableRow::total));
        table.getColumns().add(column("First seen", r -> ReportCharts.day(r.firstSeen())));
        table.getColumns().add(column("Last seen", r -> ReportCharts.day(r.lastSeen())));
        table.getColumns().add(column("Issues", ReportCharts.TableRow::issues));
        table.getColumns().getFirst().setPrefWidth(280);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No problems to draw."));
        table.getItems().setAll(all);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, row) -> {
            if (row != null) pick(row.value());
        });
        return table;
    }

    private static <T extends Comparable<T>> TableColumn<ReportCharts.TableRow, T> column(
            String title, Function<ReportCharts.TableRow, T> cell) {
        TableColumn<ReportCharts.TableRow, T> column = new TableColumn<>(title);
        column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(cell.apply(c.getValue())));
        return column;
    }

    private void pickOnClick(Node node, String value) {
        node.setOnMouseClicked(e -> pick(value));
        node.setStyle((node.getStyle() == null ? "" : node.getStyle()) + " -fx-cursor: hand;");
    }

    /** A bar's node, once the chart made it, lists {@code value}'s problems when clicked (none for null). */
    private void clickable(ObservableValue<Node> node, String value, String tip) {
        node.addListener((o, was, now) -> {
            if (now == null) return;
            Tooltip.install(now, new Tooltip(tip));
            if (value != null) {
                now.setOnMouseClicked(e -> pick(value));
                now.setStyle("-fx-cursor: hand;");
            }
        });
    }

    private void showLinks(ReportCharts.Row row) {
        links.getChildren().clear();
        if (row == null) return;
        ReportCharts.botLine(row.fingerprint()).ifPresent(at -> {
            Hyperlink line = new Hyperlink("Open " + simpleName(at.getKey()) + ":" + at.getValue());
            line.setOnAction(e -> reveal(row));
            line.setDisable(RunTelemetry.sourceFile(sourceRoot, at.getKey()).isEmpty());
            links.getChildren().add(line);
        });
        target.ifPresent(t -> row.issues().forEach(issue -> {
            Hyperlink link = new Hyperlink("#" + issue.number() + (issue.open() ? "" : " (closed)"));
            link.setTooltip(new Tooltip(issue.title()));
            link.setOnAction(e -> BrowserLauncher.open(
                    "https://github.com/" + t.owner() + "/" + t.repo() + "/issues/" + issue.number()));
            links.getChildren().add(link);
        }));
        if (links.getChildren().isEmpty()) {
            links.getChildren().add(new Label("This problem names no line of this bot."));
        }
    }

    private void reveal(ReportCharts.Row row) {
        if (row == null) return;
        ReportCharts.botLine(row.fingerprint()).ifPresent(at -> RunTelemetry.sourceFile(sourceRoot, at.getKey())
                .ifPresent(file -> onReveal.accept(file, at.getValue())));
    }

    /** {@code Warning · Bot:42 · Mouse.click ×12 · #3 #9}. */
    static String rowText(ReportCharts.Row row) {
        String[] parts = row.fingerprint().split("\\|", -1);
        StringBuilder out = new StringBuilder(row.level().displayName()).append(" · ")
                .append(simpleName(parts[0]));
        if (parts.length > 1) out.append(" · ").append(simpleName(parts[1]));
        out.append(" ×").append(row.count());
        row.issues().forEach(i -> out.append(" #").append(i.number()));
        return out.toString();
    }

    /** {@code com.x.Bot:42} → {@code Bot:42}; {@code com.x.Mouse.click} → {@code Mouse.click}. */
    private static String simpleName(String qualified) {
        int colon = qualified.indexOf(':');
        String name = colon < 0 ? qualified : qualified.substring(0, colon);
        String tail = colon < 0 ? "" : qualified.substring(colon);
        String[] parts = name.split("\\.");
        int keep = 1;
        // A method keeps its class: the part before the last that starts upper-case.
        if (parts.length > 1 && !parts[parts.length - 1].isEmpty()
                && Character.isLowerCase(parts[parts.length - 1].charAt(0))) keep = 2;
        int from = Math.max(0, parts.length - keep);
        return String.join(".", Arrays.asList(parts).subList(from, parts.length)) + tail;
    }

    private static <T> StringConverter<T> named(Function<T, String> name) {
        return new StringConverter<>() {
            @Override
            public String toString(T t) {
                return t == null ? "" : name.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }
}
