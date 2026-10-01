package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.runtime.RunTelemetry;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * The <b>Trace</b> bottom tab (2026-09-29, {@code docs/refactor/40-run-trace.md}): the running bot's debug lines
 * as the {@link TraceLine}s Studio read off its telemetry channel, filtered by level, by the source that wrote
 * them and by text, with a click on a line landing on the block that wrote it.
 *
 * <p>Beside the Run tab, not instead of it: the console is everything the bot prints, a bot's own {@code
 * System.out} included, and this is the part of it that says what wrote it and where. A run's trace starts
 * empty; the last {@value #MAX_LINES} lines are kept, since a bot left running all night would otherwise hold
 * every line it ever wrote.
 *
 * <p>The filter is {@code static} and free of JavaFX ({@link #matches}), so the one piece of logic here that can
 * be wrong is testable headlessly.
 */
final class TracePanel {

    static final int MAX_LINES = 5_000;
    static final String ALL_SOURCES = "All sources";

    private static final String TRACE_CELL = "trace-cell";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final Path sourceRoot;
    private final BiConsumer<Path, Integer> onReveal;

    private final Deque<TraceLine> all = new ArrayDeque<>();
    private final ObservableList<TraceLine> shown = FXCollections.observableArrayList();
    private final ListView<TraceLine> list = new ListView<>(shown);
    private final Map<TraceLine.Level, ToggleButton> levelFilters = new EnumMap<>(TraceLine.Level.class);
    private final ComboBox<String> sourceFilter = new ComboBox<>();
    private final Set<String> sources = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final TextField search = new TextField();
    private final Label summary = new Label();
    private final TraceWriters writers;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private final VBox node;

    TracePanel(EventBus eventBus, Path sourceRoot, ProjectSettingsService settings,
               BiConsumer<Path, Integer> onReveal) {
        this.sourceRoot = sourceRoot;
        this.onReveal = onReveal;
        this.writers = new TraceWriters(this::groupOf, hidden -> {
            if (settings != null && settings.current() != null) {
                settings.update(settings.current().withHiddenTraceWriters(hidden));
            }
            refilter();
        });
        if (settings != null && settings.current() != null) {
            writers.load(settings.current().hiddenTraceWriters());
        }

        HBox bar = new HBox(new Label("Show: "));
        for (TraceLine.Level level : List.of(TraceLine.Level.DEBUG, TraceLine.Level.INFO,
                TraceLine.Level.WARN, TraceLine.Level.ERROR)) {
            ToggleButton toggle = new ToggleButton(level.displayName());
            toggle.setSelected(true);
            toggle.getStyleClass().addAll("severity-filter", "trace-filter--" + level.id());
            toggle.setOnAction(e -> refilter());
            levelFilters.put(level, toggle);
            bar.getChildren().add(toggle);
        }
        sourceFilter.getItems().add(ALL_SOURCES);
        sourceFilter.setValue(ALL_SOURCES);
        sourceFilter.setTooltip(new Tooltip("Only the lines one part of the bot wrote"));
        sourceFilter.setOnAction(e -> refilter());
        search.setPromptText("Search");
        search.textProperty().addListener((o, was, now) -> refilter());
        HBox.setHgrow(search, Priority.SOMETIMES);
        Button clear = new Button("Clear");
        clear.setOnAction(e -> clear());
        ToggleButton writersToggle = new ToggleButton("Writers");
        writersToggle.setTooltip(new Tooltip("Pick, class by class and method by method, whose lines are shown. "
                + "A plugin's calls are traced without it writing anything; one unticked here is not traced at "
                + "all from the next run."));
        writers.offer(traceable());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        bar.getChildren().addAll(sourceFilter, search, writersToggle, gap, summary, clear);
        bar.getStyleClass().add("diagnostics-filter-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        list.setPlaceholder(new Label("Run the bot to see its trace. 🐞 Debug on the toolbar decides whether "
                + "debug lines are written."));
        list.setCellFactory(v -> new TraceCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) reveal(list.getSelectionModel().getSelectedItem());
        });
        HBox.setHgrow(list, Priority.ALWAYS);
        VBox writerPane = writers.node();
        writerPane.setVisible(false);
        writerPane.setManaged(false);
        writersToggle.selectedProperty().addListener((o, was, now) -> {
            if (now) writers.rebuild();
            writerPane.setVisible(now);
            writerPane.setManaged(now);
        });
        HBox body = new HBox(writerPane, list);
        VBox.setVgrow(body, Priority.ALWAYS);
        node = new VBox(bar, body);
        showSummary();

        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.TraceLineEvent.class, e -> add(e.line()), true));
        // A run's trace is that run's: the next one starts on an empty tab. The plugins may have changed since.
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> {
            clear();
            writers.offer(traceable());
        }, true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class,
                e -> clear(), true));
    }

    VBox node() {
        return node;
    }

    /** Drops this tab's handlers from the project's bus: the next window builds its own. */
    void dispose() {
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
    }

    /**
     * Whether {@code line} survives the filter bar: its writer is not hidden ({@link TraceWriters#hides}), its
     * level is shown (a level this Studio does not know is always shown — hiding what nobody asked to hide is the
     * worse failure), its source is the chosen one, and its source or text contains the search, ignoring case.
     */
    static boolean matches(TraceLine line, Set<String> hiddenWriters, Set<TraceLine.Level> levels, String source,
                           String search) {
        if (TraceWriters.hides(hiddenWriters, line)) return false;
        if (line.level() != TraceLine.Level.UNKNOWN && !levels.contains(line.level())) return false;
        if (source != null && !source.isEmpty() && !source.equals(ALL_SOURCES)
                && !source.equalsIgnoreCase(line.source())) return false;
        if (search == null || search.isBlank()) return true;
        String wanted = search.trim().toLowerCase(Locale.ROOT);
        return line.text().toLowerCase(Locale.ROOT).contains(wanted)
                || line.source().toLowerCase(Locale.ROOT).contains(wanted);
    }

    /** One row: when, what wrote it, what it said, and how many times. */
    static String render(TraceLine line) {
        StringBuilder text = new StringBuilder(TIME.format(line.at())).append("  ");
        if (!line.source().isEmpty()) text.append('[').append(line.source()).append("] ");
        text.append(line.text());
        if (line.count() > 1) text.append("  (×").append(line.count()).append(')');
        return text.toString();
    }

    /** The file and line a click lands on, or empty when the bot did not say, or the class is not this bot's. */
    static Optional<Map.Entry<Path, Integer>> target(Path sourceRoot, TraceLine line) {
        if (line == null || line.line().isEmpty()) return Optional.empty();
        return RunTelemetry.sourceFile(sourceRoot, line.className())
                .map(file -> Map.entry(file, line.line().getAsInt()));
    }

    void add(TraceLine line) {
        if (line == null) return;
        all.addLast(line);
        if (all.size() > MAX_LINES) {
            TraceLine dropped = all.removeFirst();
            if (!shown.isEmpty() && shown.getFirst() == dropped) shown.removeFirst();
        }
        if (!line.source().isEmpty() && sources.add(line.source())) {
            List<String> items = new ArrayList<>();
            items.add(ALL_SOURCES);
            items.addAll(sources);
            String chosen = sourceFilter.getValue();
            sourceFilter.getItems().setAll(items);
            sourceFilter.setValue(chosen);
        }
        writers.saw(line);
        if (matches(line, writers.hidden(), shownLevels(), sourceFilter.getValue(), search.getText())) {
            shown.add(line);
            // Follow the run unless the user has picked a line to look at.
            if (list.getSelectionModel().isEmpty()) list.scrollTo(shown.size() - 1);
        }
        showSummary();
    }

    void clear() {
        all.clear();
        shown.clear();
        showSummary();
    }

    /** What is listed now, for a test. */
    List<TraceLine> shown() {
        return List.copyOf(shown);
    }

    private Set<TraceLine.Level> shownLevels() {
        Set<TraceLine.Level> levels = EnumSet.noneOf(TraceLine.Level.class);
        levelFilters.forEach((level, toggle) -> {
            if (toggle.isSelected()) levels.add(level);
        });
        return levels;
    }

    private void refilter() {
        Set<TraceLine.Level> levels = shownLevels();
        Set<String> hidden = writers.hidden();
        String source = sourceFilter.getValue();
        String text = search.getText();
        shown.setAll(all.stream().filter(l -> matches(l, hidden, levels, source, text)).toList());
        showSummary();
    }

    private void showSummary() {
        summary.setText(all.isEmpty() ? "" : shown.size() == all.size() ? all.size() + " lines"
                : shown.size() + " of " + all.size() + " lines");
    }

    private void reveal(TraceLine line) {
        target(sourceRoot, line).ifPresent(at -> onReveal.accept(at.getKey(), at.getValue()));
    }

    /** The classes a run traces calls into: the offered classes of the project's plugins ({@code BotJvm.traceAgent}). */
    private static List<String> traceable() {
        return PluginHost.menuFacades().stream().map(f -> f.type().getName()).toList();
    }

    /** Who ships {@code className}: this bot when its source is here, else the plugin whose jar holds it. */
    private String groupOf(String className) {
        if (RunTelemetry.sourceFile(sourceRoot, className).isPresent()) return TraceWriters.THIS_BOT;
        return PluginHost.pluginNameOwning(className).orElse(TraceWriters.LIBRARIES);
    }

    /** A row's right-click: hide what wrote it, by method or by class, or show every writer again. */
    private ContextMenu rowMenu(TraceLine line) {
        ContextMenu menu = new ContextMenu();
        if (!line.writerClass().isEmpty()) {
            String name = TraceWriters.simpleName(line.writerClass());
            MenuItem method = new MenuItem("Hide lines from " + name + "." + line.writerMethod() + "()");
            method.setOnAction(e -> writers.hide(TraceWriters.key(line.writerClass(), line.writerMethod())));
            MenuItem type = new MenuItem("Hide all lines from " + name);
            type.setOnAction(e -> writers.hide(line.writerClass()));
            menu.getItems().addAll(method, type);
        }
        MenuItem all = new MenuItem("Show every writer");
        all.setDisable(writers.hidden().isEmpty());
        all.setOnAction(e -> writers.showAll());
        menu.getItems().add(all);
        return menu;
    }

    /** A row coloured by its level; the tooltip says where it came from, or that it cannot be followed. */
    private final class TraceCell extends ListCell<TraceLine> {
        @Override
        protected void updateItem(TraceLine line, boolean empty) {
            super.updateItem(line, empty);
            // A recycled cell keeps its classes, so a blank row would otherwise keep the last line's colour.
            getStyleClass().removeIf(c -> c.startsWith(TRACE_CELL + "--"));
            if (!getStyleClass().contains(TRACE_CELL)) getStyleClass().add(TRACE_CELL);
            if (empty || line == null) {
                setText(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            setText(render(line));
            setContextMenu(rowMenu(line));
            getStyleClass().add(TRACE_CELL + "--" + (line.level() == TraceLine.Level.UNKNOWN
                    ? "other" : line.level().id()));
            Optional<Map.Entry<Path, Integer>> at = target(sourceRoot, line);
            setTooltip(new Tooltip(at.map(e -> "Click to show " + e.getKey().getFileName() + ", line "
                    + e.getValue()).orElse("The bot did not say which of its lines wrote this.")));
        }
    }
}
