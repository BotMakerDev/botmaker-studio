package com.botmaker.studio.ui.app.reports;

import com.botmaker.cli.reports.IssueReports;
import com.botmaker.cli.reports.Reports;
import com.botmaker.cli.reports.RunReports;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.api.Dimension;
import com.botmaker.runlog.api.Level;
import com.botmaker.studio.assist.PastRuns;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.RunTelemetry;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Reports tab (run reports spec §5): a bot's problems, from its runs on this computer or from the issues its
 * runners sent, grouped by one of the dimensions the reports name (Studio knows none) — a bar per value stacked by
 * level, or a bar per day stacked by value. A bar lists its problems; a problem opens its bot line or its issues.
 *
 * <p>Issue titles and values are written by runners: shown as text, never acted on.
 */
public final class ReportsPanel {

    /** Bars drawn at most, per chart. */
    static final int MOST = 15;
    /** Values stacked at most per day; the rest are {@link ReportCharts#OTHER}. */
    static final int MOST_PER_DAY = 6;

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
    enum View {
        TOTALS("Totals"), BY_DAY("By day");

        private final String displayName;

        View(String displayName) {
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

    private final Path sourceRoot;
    private final BiConsumer<Path, Integer> onReveal;
    private final Optional<ReportTarget> target;
    private final Supplier<Reports> runs;
    private final Function<ReportTarget, Reports> issues;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    /** The newest load: an older one that ends later is dropped. */
    private final AtomicInteger loads = new AtomicInteger();

    private final BorderPane root = new BorderPane();
    private final ComboBox<Source> source = new ComboBox<>();
    private final ComboBox<Dimension> groupBy = new ComboBox<>();
    private final ComboBox<View> view = new ComboBox<>();
    private final ComboBox<Only> only = new ComboBox<>();
    private final Label notes = new Label();
    private final StackPane chartHolder = new StackPane();
    private final Label picked = new Label("Click a bar to list its problems.");
    private final ListView<ReportCharts.Row> rows = new ListView<>();
    private final FlowPane links = new FlowPane(8, 4);

    private Reports shown;
    private String pickedValue;

    /** The Reports tab of the project in {@code project}; a problem's line opens through {@code onReveal}. */
    public ReportsPanel(EventBus eventBus, Path project, Path sourceRoot, BiConsumer<Path, Integer> onReveal) {
        this(sourceRoot, onReveal, ReportTarget.of(project),
                () -> RunReports.read(project.resolve(RunLog.FOLDER)),
                t -> new IssueReports(PastRuns.signedIn(), IssueReports.defaultCache()).read(t));
        // A run's end adds its problems to this computer's.
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.BotRunEndedEvent.class, e -> {
            if (source.getValue() == Source.RUNS) load();
        }, true));
    }

    ReportsPanel(Path sourceRoot, BiConsumer<Path, Integer> onReveal, Optional<ReportTarget> target,
                 Supplier<Reports> runs, Function<ReportTarget, Reports> issues) {
        this.sourceRoot = sourceRoot;
        this.onReveal = onReveal;
        this.target = target;
        this.runs = runs;
        this.issues = issues;
        build();
        load();
    }

    public Node node() {
        return root;
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
        groupBy.setConverter(named(Dimension::label));
        view.getItems().setAll(View.values());
        view.setValue(View.TOTALS);
        view.setConverter(named(View::displayName));
        only.getItems().setAll(Only.values());
        only.setValue(Only.ALL);
        only.setConverter(named(Only::displayName));
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> load());

        source.setOnAction(e -> load());
        groupBy.setOnAction(e -> redraw());
        view.setOnAction(e -> redraw());
        only.setOnAction(e -> redraw());

        HBox bar = new HBox(8, new Label("From"), source, new Label("Group by"), groupBy, view, only, refresh, notes);
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
        String was = groupBy.getValue() == null ? null : groupBy.getValue().id();
        List<Dimension> dims = reports.report().dimensions();
        groupBy.getItems().setAll(dims);
        groupBy.setValue(dims.stream().filter(d -> d.id().equals(was)).findFirst()
                .or(() -> dims.stream().filter(d -> d.id().equals("botLine")).findFirst())
                .orElse(dims.isEmpty() ? null : dims.getFirst()));
        List<String> said = new ArrayList<>(reports.notes());
        if (reports.report().occurrences().isEmpty()) said.add("No problems.");
        notes.setText(String.join(" ", said));
        redraw();
    }

    private void redraw() {
        pick(null);
        Dimension dim = groupBy.getValue();
        if (shown == null || dim == null) {
            chartHolder.getChildren().setAll(new Label("No problems to draw."));
            return;
        }
        Reports counted = counted();
        chartHolder.getChildren().setAll(view.getValue() == View.BY_DAY
                ? byDay(ReportCharts.byDay(counted.report(), dim.id(), MOST_PER_DAY))
                : totals(ReportCharts.totals(counted.report(), dim.id(), MOST)));
    }

    private Reports counted() {
        return shown.filtered(only.getValue() == null ? null : only.getValue().level, null);
    }

    /** Lists the problems of {@code value} of the dimension grouped by; null clears the list. */
    void pick(String value) {
        pickedValue = value;
        Dimension dim = groupBy.getValue();
        if (value == null || dim == null || shown == null) {
            picked.setText("Click a bar to list its problems.");
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
