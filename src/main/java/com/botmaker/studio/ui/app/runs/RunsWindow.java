// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app.runs;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.plugin.HostServices;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.params.JavaParameter;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.core.run.claims.LiveRunsView;
import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.core.run.claims.SeveralAsk;
import com.botmaker.core.run.claims.SeveralRuns;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.plugin.api.run.PluginPool;
import com.botmaker.plugin.api.run.PoolOffer;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.studio.plugin.FxUi;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Runs window (spec {@code 2026-11-15-run-claims-design.md} §7, plan phase 14, the user's picks H-A and P-A).
 * From Studio's home it lists every live run on this PC grouped by bot, each group with <i>Start more</i> and
 * <i>Stop all</i>; from a project, ▶ Run's <i>Run several…</i>, it is this bot's runs: the live ones, then the rows a
 * count deals for <i>Start N more</i>, a pick pinned by a click in its cell, the other bots' runs holding what this
 * bot's rows need greyed, the capacity line with the plugins' offers, and the shared parameters named below.
 *
 * <p>The registry of live runs is read every two seconds while the window shows; the dealing is the core's, asked
 * through {@link Several}. The window starts, deals and decides nothing itself.
 */
public final class RunsWindow {

    /** What starts a project's runs: the core's {@code runSeveral}, asked off the FX thread. */
    @FunctionalInterface
    public interface Several {
        CoreEvent.RunsStarted run(SeveralAsk ask);
    }

    /**
     * The project the window is filtered to.
     *
     * @param parameters opens the Parameters window, where a pool is added or changed
     */
    public record Project(ProjectConfig config, ProjectState state, Several several, Runnable parameters,
                          Runnable showAll) {
    }

    private static final Duration READ_EVERY = Duration.seconds(2);
    /** Where home's <i>Start more</i> asked to go: opened by the project's window, which then shows Run several. */
    private static volatile Path pendingSeveral;

    private final Stage stage = new Stage();
    private final Optional<Project> project;
    private final Consumer<Path> startRunsOf;
    private final Supplier<List<Path>> bots;
    private final Timeline reading = new Timeline(new KeyFrame(READ_EVERY, e -> read()));
    private final AtomicBoolean busyReading = new AtomicBoolean();
    private final VBox body = new VBox(10);
    private final Label header = new Label();
    private LiveRunsView view = new LiveRunsView(List.of());
    private boolean built;
    /** Home's ▶ Start runs of…, its items My bots as listed when the window last showed. */
    private MenuButton startOf;
    private final AtomicBoolean botsAsked = new AtomicBoolean();

    // A project's: what is asked, the count's answer, the pins, and what the last start said.
    private final Spinner<Integer> asked = new Spinner<>(1, SeveralRuns.MOST, 1);
    private final Button start = new Button("▶ Start");
    private Optional<CoreEvent.RunsStarted> counted = Optional.empty();
    private final Map<Integer, Map<String, List<String>>> pins = new LinkedHashMap<>();
    private String said = "";
    private boolean counting;
    private boolean countAgain;

    private RunsWindow(Optional<Project> project, Consumer<Path> startRunsOf, Supplier<List<Path>> bots) {
        this.project = project;
        this.startRunsOf = startRunsOf;
        this.bots = bots;
        reading.setCycleCount(Timeline.INDEFINITE);
        stage.setTitle(project.map(p -> "Runs — " + p.config().projectName()).orElse("Runs on this PC"));
        stage.setOnHidden(e -> reading.stop());
    }

    /**
     * Home's window: every live run on this PC. {@code startRunsOf} opens a bot to start its runs there, and
     * {@code bots} lists My bots' folders for <i>Start runs of…</i>.
     */
    public static RunsWindow home(Consumer<Path> startRunsOf, Supplier<List<Path>> bots) {
        return new RunsWindow(Optional.empty(), startRunsOf, bots);
    }

    /** A project's window, filtered to its bot. */
    public static RunsWindow of(Project project) {
        return new RunsWindow(Optional.of(project), dir -> {}, List::of);
    }

    /** Asks the window of the bot in {@code dir}, once it opens, to show its runs: home's <i>Start more</i>. */
    public static void showSeveralWhenOpened(Path dir) {
        pendingSeveral = dir.toAbsolutePath().normalize();
    }

    /**
     * Whether home asked for the runs of the bot in {@code dir}. The ask is spent by the next project to open,
     * whichever it is, so an open that failed never shows the window hours later.
     */
    public static boolean takeSeveralAsked(Path dir) {
        Path pending = pendingSeveral;
        pendingSeveral = null;
        return pending != null && pending.equals(dir.toAbsolutePath().normalize());
    }

    /** How many runs are live on this PC, every bot's: the count home's <i>Runs</i> shows. Off the FX thread. */
    public static int liveCount() {
        return RunRegistry.ofThisPc().live().size();
    }

    /** Shows the window in front of {@code owner}, read again. */
    public void show(Window owner) {
        if (!built) build(owner);
        botsAsked.set(true);
        read();
        if (project.isPresent()) count();
        stage.show();
        stage.toFront();
        reading.play();
    }

    /** Closes the window for good. */
    public void dispose() {
        reading.stop();
        stage.close();
    }

    public Stage stage() {
        return stage;
    }

    private void build(Window owner) {
        built = true;
        if (owner != null && !Double.isNaN(owner.getX())) {
            stage.setX(owner.getX() + 60);
            stage.setY(owner.getY() + 40);
        }
        header.getStyleClass().add("runner-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, header, spacer);
        top.setAlignment(Pos.CENTER_LEFT);
        if (project.isEmpty()) {
            MenuButton startOf = new MenuButton("▶ Start runs of…");
            startOf.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
            startOf.getItems().add(new MenuItem("Looking for your bots…"));
            this.startOf = startOf;
            top.getChildren().add(startOf);
        } else {
            // Its arrows only, [− n +]: typed text would reach the factory's converter unchecked.
            asked.setPrefWidth(80);
            asked.valueProperty().addListener((o, was, now) -> {
                pins.keySet().removeIf(row -> row >= now);
                said = "";
                count();
            });
            start.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
            start.setOnAction(e -> start());
            Button all = new Button("Show all runs on this PC");
            all.setOnAction(e -> project.get().showAll().run());
            top.getChildren().addAll(new Label("Start"), asked, new Label("more"), start, all);
        }
        top.getStyleClass().add("runner-header");
        top.setPadding(new Insets(8, 16, 8, 16));
        body.setPadding(new Insets(12, 16, 16, 16));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane(scroll);
        root.setTop(top);
        stage.setScene(ThemedWindows.scene(root, 900, 620));
    }

    /** Reads the registry off the FX thread, then draws. */
    private void read() {
        if (!busyReading.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("runs-window-read").start(() -> {
            try {
                LiveRunsView read = LiveRunsView.of(RunRegistry.ofThisPc());
                // My bots for ▶ Start runs of…, listed here: listing opens every bot's repository.
                List<Path> mine = project.isEmpty() && botsAsked.getAndSet(false) ? bots.get() : null;
                Platform.runLater(() -> {
                    view = read;
                    if (mine != null) showBots(mine);
                    draw();
                });
            } catch (RuntimeException unreadable) {
                // The registry or a bot unreadable for now; the next read says.
            } finally {
                busyReading.set(false);
            }
        });
    }

    private void showBots(List<Path> mine) {
        startOf.getItems().setAll(mine.stream().map(dir -> {
            MenuItem item = new MenuItem(dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
            item.setOnAction(a -> startRunsOf.accept(dir));
            return item;
        }).toList());
        if (mine.isEmpty()) startOf.getItems().add(new MenuItem("No bot of yours yet"));
    }

    private void draw() {
        if (project.isPresent()) drawProject(project.get());
        else drawHome();
    }

    // --- Home (H-A) ---

    private void drawHome() {
        header.setText("Runs on this PC  ·  " + view.count() + " live · " + view.groups().size()
                + (view.groups().size() == 1 ? " bot" : " bots"));
        List<Node> groups = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LiveRunsView.Group group : view.groups()) {
            Label name = new Label(group.name());
            name.getStyleClass().add("runs-group-name");
            Label kind = new Label(group.kind().displayName() + " · " + runs(group.runs().size()));
            kind.getStyleClass().add("gallery-card-note");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox head = new HBox(8, name, kind, spacer);
            head.setAlignment(Pos.CENTER_LEFT);
            if (group.dir().isPresent()) {
                Button more = new Button("Start more");
                more.setOnAction(e -> startRunsOf.accept(group.dir().get()));
                Button stopAll = new Button("Stop all");
                stopAll.setOnAction(e -> stop(group.runs()));
                head.getChildren().addAll(more, stopAll);
            }
            GridPane table = grid(4);
            int r = 0;
            for (LiveRunsView.Live run : group.runs()) {
                String picks = run.picks().values().stream().flatMap(List::stream).map(RunsRows::shown)
                        .collect(Collectors.joining("  "));
                Label first = styled("● " + (picks.isEmpty() ? run.runId() : picks), "runs-live");
                table.addRow(r++, first, new Label(RunsRows.since(run.startedMillis(), now)),
                        new Label(run.lastLine()), group.dir().isPresent() ? stopButton(run) : new Label());
            }
            VBox box = new VBox(6, head, table);
            box.getStyleClass().add("runs-group");
            groups.add(box);
        }
        if (groups.isEmpty()) groups.add(note("No run is live on this PC. ▶ Start runs of… picks a bot."));
        body.getChildren().setAll(groups);
    }

    private Button stopButton(LiveRunsView.Live run) {
        Button stop = new Button("■");
        stop.setOnAction(e -> stop(List.of(run)));
        return stop;
    }

    /** Stops {@code runs}' processes, found again by run id; the registry drops each as it next reads it. */
    private void stop(List<LiveRunsView.Live> runs) {
        Set<String> ids = runs.stream().map(LiveRunsView.Live::runId).collect(Collectors.toSet());
        Thread.ofVirtual().name("runs-window-stop").start(() -> {
            for (RunRegistry.LiveRun live : RunRegistry.ofThisPc().live()) {
                if (ids.contains(live.runId())) SeveralRuns.stop(live);
            }
            Platform.runLater(this::read);
        });
    }

    // --- A project (P-A) ---

    private void drawProject(Project p) {
        Path dir = p.config().projectPath();
        Optional<LiveRunsView.Group> mine = view.groupOf(dir);
        int live = mine.map(g -> g.runs().size()).orElse(0);
        header.setText("Runs of this bot  ·  " + live + " live");
        RunsRows table = RunsRows.of(mine, counted, asked.getValue(), view.others(dir), System.currentTimeMillis());
        start.setText("▶ Start " + table.ready());
        start.setDisable(counting || table.ready() == 0);

        List<Node> nodes = new ArrayList<>();
        GridPane grid = grid(table.columns().size() + 1);
        int r = 0;
        List<Node> heads = new ArrayList<>();
        for (String column : table.columns()) heads.add(styled(RunsRows.heading(column), "gallery-card-note"));
        heads.add(styled("status", "gallery-card-note"));
        grid.addRow(r++, heads.toArray(Node[]::new));
        for (RunsRows.Row row : table.rows()) {
            String style = switch (row.kind()) {
                case LIVE -> "runs-live";
                case READY -> "runs-ready";
                case MISSING -> "runs-missing";
                case OTHER -> "runs-other";
            };
            List<Node> cells = new ArrayList<>();
            for (String column : table.columns()) cells.add(cell(row, column, style));
            cells.add(styled((row.kind() == RunsRows.Kind.LIVE ? "● " : row.kind() == RunsRows.Kind.READY ? "◆ "
                    : row.kind() == RunsRows.Kind.MISSING ? "◇ " : "") + row.status(), style));
            grid.addRow(r++, cells.toArray(Node[]::new));
        }
        nodes.add(grid);
        if (table.columns().isEmpty() && counted.isPresent()) {
            nodes.add(note("No pool deals this bot's runs: every run gets the same values. Add a pool in Parameters."));
        }
        if (counting) nodes.add(note("Counting what can run…"));
        else counted.ifPresent(c -> nodes.add(capacity(c, table)));
        if (!said.isEmpty()) nodes.add(note(said));

        String shared = shared(p, table.columns());
        Button parameters = new Button("Parameters…");
        parameters.setOnAction(e -> p.parameters().run());
        Label sharedLine = note(shared.isEmpty() ? "Every parameter is dealt by a pool."
                : "Shared by every run: " + shared + ". Change them, or add a pool, in Parameters.");
        sharedLine.setWrapText(true);
        HBox foot = new HBox(10, sharedLine, parameters);
        foot.setAlignment(Pos.CENTER_LEFT);
        nodes.add(foot);
        nodes.add(note("A click on a ready row's cell pins that pick. A run's Claims in code are decided while it runs."));
        body.getChildren().setAll(nodes);
    }

    /** A cell: a ready row's is clicked to pin its pick, or to free it again. */
    private Node cell(RunsRows.Row row, String column, String style) {
        List<String> picks = row.picks().getOrDefault(column, List.of());
        String text = picks.stream().map(RunsRows::shown).collect(Collectors.joining("  "));
        Label cell = styled(text.isEmpty() ? "—" : text, style);
        if (row.kind() != RunsRows.Kind.READY || picks.isEmpty()) return cell;
        boolean pinned = picks.equals(pins.getOrDefault(row.index(), Map.of()).get(column));
        if (pinned) {
            cell.setText(cell.getText() + " 📌");
            cell.getStyleClass().add("runs-pinned");
        }
        cell.getStyleClass().add("runs-cell");
        cell.setOnMouseClicked(e -> {
            Map<String, List<String>> rowPins = pins.computeIfAbsent(row.index(), i -> new LinkedHashMap<>());
            if (pinned) rowPins.remove(column);
            else rowPins.put(column, picks);
            if (rowPins.isEmpty()) pins.remove(row.index());
            said = "";
            count();
        });
        return cell;
    }

    /** {@code 3 of 4 can run: …}, with each plugin offer as a button. */
    private Node capacity(CoreEvent.RunsStarted count, RunsRows table) {
        Label words = new Label(count.words());
        words.setWrapText(true);
        words.getStyleClass().add("runs-group-name");
        FlowPane line = new FlowPane(8, 6, words);
        int missing = asked.getValue() - table.ready();
        for (String title : count.offers()) {
            Button offer = new Button(title);
            offer.setOnAction(e -> runOffer(title, asked.getValue(), missing));
            line.getChildren().add(offer);
        }
        for (String warning : count.warnings()) line.getChildren().add(styled(warning, "runs-missing"));
        return line;
    }

    /**
     * Runs the plugin offer titled {@code title}, as its plugin gives it again for the same shortfall: the core
     * listed it by title only, and the action is the plugin's own, run here on the user's click.
     */
    private void runOffer(String title, int askedRuns, int missing) {
        StudioServices services = HostServices.forProject(project.orElseThrow().config(), FxUi.ownedBy(() -> stage));
        PluginPool.Shortfall shortfall = new PluginPool.Shortfall() {
            @Override
            public int asked() {
                return askedRuns;
            }

            @Override
            public int missing() {
                return Math.max(missing, 1);
            }
        };
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            for (PluginPool<?> pool : plugin.plugin().pools()) {
                try {
                    for (PoolOffer offer : pool.offers(shortfall, services)) {
                        if (offer.title().equals(title)) {
                            offer.run(services);
                            said = "";
                            count();
                            return;
                        }
                    }
                } catch (RuntimeException | LinkageError e) {
                    // That plugin offers nothing now; the next may still offer it.
                }
            }
        }
        said = "\"" + title + "\" is no longer offered. The count is read again.";
        count();
    }

    /** Asks the core what the runs asked for would be dealt, holding nothing. */
    private void count() {
        Project p = project.orElseThrow();
        if (counting) {
            countAgain = true;
            return;
        }
        counting = true;
        countAgain = false;
        SeveralAsk ask = new SeveralAsk(asked.getValue(), SeveralAsk.How.COUNT, Map.copyOf(pins));
        if (built) draw();
        // What the last start said stays until the user changes the ask: the count after a start keeps it.
        ask(p, ask, result -> counted = Optional.of(result));
    }

    /** Starts the rows the count dealt, as pinned. */
    private void start() {
        Project p = project.orElseThrow();
        if (counting) return;
        counting = true;
        SeveralAsk ask = new SeveralAsk(asked.getValue(), SeveralAsk.How.START, Map.copyOf(pins));
        draw();
        ask(p, ask, result -> {
            said = result.words();
            pins.clear();
            counted = Optional.empty();
            countAgain = true;
        });
    }

    private void ask(Project p, SeveralAsk ask, Consumer<CoreEvent.RunsStarted> heard) {
        Thread.ofVirtual().name("runs-window-ask").start(() -> {
            String refused = null;
            CoreEvent.RunsStarted result = null;
            try {
                result = p.several().run(ask);
            } catch (RuntimeException e) {
                // A refusal says why; anything else still frees the window for the next ask.
                refused = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            CoreEvent.RunsStarted answer = result;
            String why = refused;
            Platform.runLater(() -> {
                counting = false;
                if (answer != null) heard.accept(answer);
                else {
                    said = why == null ? "The core did not answer." : why;
                    counted = Optional.empty();
                }
                read();
                if (countAgain) count();
                else draw();
            });
        });
    }

    /** The bot's own parameters no pool fills, {@code Farm.plan()} each. */
    private static String shared(Project p, List<String> columns) {
        try {
            List<JavaParameter> own = JavaParameters.scan(p.config(), p.state()).stream()
                    .filter(parameter -> !parameter.declaredByPlugin()).toList();
            Function<JavaParameter, Boolean> pooled = parameter -> columns.stream().anyMatch(column ->
                    RunsRows.owner(column).filter(parameter.className()::equals).isPresent()
                            && RunsRows.heading(column).equals(parameter.name() + "()"));
            return own.stream().filter(parameter -> !pooled.apply(parameter))
                    .map(parameter -> parameter.className() + "." + parameter.name() + "()")
                    .collect(Collectors.joining(" · "));
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static GridPane grid(int columns) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(4);
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setHgrow(i == columns - 1 ? Priority.ALWAYS : Priority.NEVER);
            grid.getColumnConstraints().add(column);
        }
        return grid;
    }

    private static Label styled(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private static Label note(String text) {
        Label label = styled(text, "gallery-card-note");
        label.setWrapText(true);
        return label;
    }

    private static String runs(int n) {
        return n + (n == 1 ? " run" : " runs");
    }
}
