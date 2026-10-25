package com.botmaker.studio.ui.app.runner;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.plugin.api.run.Readiness;
import com.botmaker.studio.project.params.ParameterRow;
import com.botmaker.studio.project.params.RunnerValues;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.runtime.StopKeyPreference;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.ui.app.home.MyBots;
import com.botmaker.studio.util.BrowserLauncher;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostParamScope;
import com.botmaker.studio.plugin.HostPluginValues;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectMode;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.JavaParameter;
import com.botmaker.studio.project.params.JavaParameters;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.ui.app.ProjectWindow;
import com.botmaker.studio.ui.app.botinfo.BotIconView;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The window someone who wants to <em>use</em> a bot gets: the settings its author chose to expose, and a Run
 * button with the log underneath.
 *
 * <p><b>Nothing here knows what an activity is (2026-09-10).</b> The window asks each loaded plugin for the
 * rows of the sections it declared, keeps the ones the author marked {@code PUBLIC}, and files them under the
 * category each row carries. An activity's <em>enable flag</em> is a parameter like any other — a yes/no row
 * of the SDK plugin's section — so the checkbox list this window used to build out of {@code activities.json}
 * is not a special case that moved, it is a special case that stopped existing. What is left is the frame:
 * the layout, the reflow, the category index and the Run button, which are the host's for the same reason a
 * toolbar's grouping and overflow are.
 *
 * <p>It is a whole separate window rather than the editor with parts hidden, and that is the entire design.
 * Hiding is a rule someone has to remember to apply to every control they add; not building the editor at all
 * is a rule that enforces itself. Nothing here constructs a {@code ToolbarManager}, a file explorer or a block
 * canvas, so no amount of later work can leak one into a user's view — and the bot's source is never even
 * parsed on this path, which is why the window opens instantly on a project the editor takes seconds over.
 *
 * <p>Two ways in, and the difference is only what the header offers:
 * <ul>
 *   <li>{@link Origin#INSTALLED} — somebody else's bot, opened for use. The header offers a look at the code
 *       (still read-only) and "Improve this bot", which is the one action here that changes what the project
 *       <em>is</em>.</li>
 *   <li>{@link Origin#PREVIEW} — its author checking what they have exposed. The header offers the way back.
 *       This mode writes no marker and leaves no trace: see {@link ProjectMode}.</li>
 * </ul>
 */
public final class RunnerWindow implements ProjectWindow {

    /** How this window was reached — which decides what the header offers, and nothing else. */
    public enum Origin { INSTALLED, PREVIEW }

    /** Widest the content column grows, so the settings don't stretch across a maximised screen. */
    private static final double CONTENT_MAX_WIDTH = 900;

    /**
     * How wide one setting card is. Fixed, because it is what makes the gallery reflow on its own: the tile
     * pane fits as many of them across as the window allows and wraps the rest, so narrow is one column and
     * wide is three without a single width listener.
     */
    private static final double TILE_WIDTH = 300;

    /** How often the header asks whether the bot can start: a game started or closed shows within this. */
    private static final Duration READINESS_EVERY = Duration.ofSeconds(5);

    private final Stage stage;
    private final Origin origin;
    private final ProjectConfig config;

    /** The open buffers — a {@code @Param} field is read from the editor's copy where there is one. */
    private final ProjectState state;
    private final EventBus eventBus;

    /** Leaves the Runner for the editor scene, without changing what the project is. Supplied by the shell. */
    private final Runnable onShowEditor;
    /** Back to the home's bot list (2026-10-25: the home opens the Runner from a card's ▶ Run). */
    private final Runnable onHome;

    /** Dropped from {@link BlockTheme}'s <b>static</b> listener list on dispose, like the editor's. */
    private final Consumer<BlockTheme.ThemeType> themeListener;
    /** Closed on dispose: the project's bus outlives this window whenever the audience is toggled. */
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();

    private Parent root;
    private TextArea log;
    private Label status;
    private RunnerHeader header;

    /** Who made the bot, its release and the catalog's newer one — read once, when the window is built. */
    private RunnerHeader.About about;
    /** The bot's catalog listing, when it has one: the commit an update must land on. */
    private Optional<GalleryEntry> listed = Optional.empty();
    /** The author's repository, for <i>What changed</i>; empty for the user's own bot. */
    private Optional<SyncModel.Slug> original = Optional.empty();

    /** The runner's values for this bot, kept apart from it ({@link RunnerValues}, 2026-10-25). */
    private RunnerValues kept;
    /** The kept values this version of the bot no longer takes, said once at the top of the settings. */
    private List<String> dropped = List.of();

    /** Asks the plugins whether the bot can start, every few seconds while the window is open. */
    private ScheduledExecutorService readinessPoll;
    /** Set when the window is left; FX thread only. */
    private boolean disposed;

    // The (group, row) pair was a record of this class until 2026-09-17, then ParameterSurface.Entry, and
    // since 2026-09-22 it is JavaParameter — the field itself, because a field is the only thing a row ever
    // comes from now. It is still a pair and never the name alone: a name identifies a parameter only inside
    // its own class, and two classes may both declare a Timeout.

    /** Every public parameter the project declares, in class order — read once, when the window is built. */
    private final List<JavaParameter> rows = new ArrayList<>();

    /** Every on-screen widget's reader, keyed by the {@code (group, name)} pair it was built from. */
    private final List<ParamValueWidgets.ValueEditor> valueEditors = new ArrayList<>();

    /** The records the bot declares, for a setting typed with one of them. Read with the rows. */
    private BotRecords records = BotRecords.none();

    /** The body's scroller and one card per category — what the category chips jump between. */
    private ScrollPane bodyScroll;
    private final Map<String, Node> categoryCards = new LinkedHashMap<>();

    public RunnerWindow(StudioContext ctx, Stage stage, Origin origin, Runnable onShowEditor, Runnable onHome) {
        this.stage = stage;
        this.origin = origin;
        this.config = ctx.config();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();
        this.onShowEditor = onShowEditor;
        this.onHome = onHome;

        BlockTheme.initialize();
        this.themeListener = theme -> { if (root != null) ThemedWindows.applyThemeClass(root); };
        BlockTheme.addThemeChangeListener(themeListener);
    }

    @Override
    public Scene createScene() {
        about = readAbout();
        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("runner-root");
        shell.setTop(header());
        shell.setCenter(body());
        shell.setBottom(runBar());

        this.root = shell;
        ThemedWindows.applyThemeClass(shell);
        // Same reason as the editor's: JavaFX reads the scene root's minimum as the window's, so a long
        // activity name must not become a width the user cannot drag below.
        shell.setMinWidth(0);
        shell.setMinHeight(0);

        subscribe();
        pollReadiness();

        // Unsized on purpose — see UIManager.createScene(): this scene lands on the shell's stage, which
        // already has the geometry the user chose, and a sized one would fight it (or, maximized, lose to it
        // and leave the window bordered in black).
        Scene scene = new Scene(shell);
        ThemedWindows.addStylesheet(scene);
        return scene;
    }

    @Override
    public void dispose() {
        BlockTheme.removeThemeChangeListener(themeListener);
        disposed = true;
        if (readinessPoll != null) readinessPoll.shutdownNow();
        for (EventBus.Subscription s : subscriptions) {
            try {
                s.close();
            } catch (Exception ignored) {
                // A subscription that is already gone is exactly the state we wanted.
            }
        }
        subscriptions.clear();
    }

    // =========================================================================
    // HEADER
    // =========================================================================

    /**
     * Header A (2026-10-25, spec § 1a): {@link RunnerHeader}, with this window's way back to editing — or, on
     * somebody else's bot, a look at the code and "Improve this bot" — after the titles.
     */
    private Node header() {
        List<Node> extra = new ArrayList<>();
        if (origin == Origin.PREVIEW) {
            Button back = new Button("← Back to editing");
            back.setOnAction(e -> onShowEditor.run());
            extra.add(back);
        } else {
            Button code = new Button("View the code");
            code.setTooltip(new Tooltip("Read how this bot works. Nothing is editable until you make it yours."));
            code.setOnAction(e -> onShowEditor.run());
            Button improve = new Button("Improve this bot");
            improve.setOnAction(e -> switchToEditorMode());
            extra.addAll(List.of(code, improve));
        }
        header = new RunnerHeader(about, BotIconView.local(about.name(), config.projectPath(),
                RunnerHeader.ICON_SIZE), StopKeyPreference.name(), extra, new RunnerHeader.Actions() {
            @Override
            public void home() {
                onHome.run();
            }

            @Override
            public void start() {
                run();
            }

            @Override
            public void stop() {
                eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
            }

            @Override
            public void update() {
                getUpdate();
            }

            @Override
            public void whatChanged() {
                original.ifPresent(s -> about.available().ifPresent(tag -> BrowserLauncher.open(
                        "https://github.com/" + s.owner() + "/" + s.repo() + "/releases/tag/" + tag)));
            }

            @Override
            public void editInStudio() {
                onShowEditor.run();
            }
        });
        return header.node();
    }

    /**
     * Who made the bot and which release it is at, off its repository, and the catalog's newer release from the
     * copy kept on disk: quick reads, done once while the window is built. The runner's kept values are found by
     * the same author ({@link RunnerValues#keyOf}).
     */
    private RunnerHeader.About readAbout() {
        Path dir = config.projectPath();
        MyBots.Facts facts = MyBots.read(dir, Map.of());
        original = facts.originalUrl().flatMap(SyncModel::slug);
        listed = original.flatMap(s -> CatalogClient.session().kept().bots().stream()
                .filter(e -> MyBots.key(e.owner(), e.repo()).equals(MyBots.key(s.owner(), s.repo())))
                .findFirst());
        Optional<String> available = listed.flatMap(e -> GalleryEntry.updateTarget(facts.version().orElse(""),
                e.tag()));
        kept = RunnerValues.load(RunnerValues.keyOf(original.map(SyncModel.Slug::owner),
                original.map(SyncModel.Slug::repo), dir));
        return new RunnerHeader.About(facts.title().orElse(config.projectName()), original.map(SyncModel.Slug::owner), facts.version(),
                available);
    }

    /**
     * Asks the plugins every few seconds whether the bot can start ({@link PluginHost#readiness}), off the FX
     * thread: a plugin may list windows or read a launcher's files to answer.
     */
    private void pollReadiness() {
        readinessPoll = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "runner-readiness");
            thread.setDaemon(true);
            return thread;
        });
        readinessPoll.scheduleWithFixedDelay(() -> {
            try {
                Optional<Readiness> said = PluginHost.readiness();
                Platform.runLater(() -> header.readiness(said));
            } catch (RuntimeException | LinkageError e) {
                // A failed ask must not end the polling: the next one may answer.
                System.err.println("Could not ask whether the bot can start: " + e);
            }
        }, 0, READINESS_EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * <i>Update</i>: {@link RunnerUpdate} off the FX thread, then a reload onto the new release, where the
     * runner's values are applied again. A conflict or a compile error is left for Studio.
     */
    private void getUpdate() {
        String tag = about.available().orElse(null);
        if (tag == null) return;
        header.updating();
        String pinned = listed.map(e -> e.commitOf(tag)).orElse("");
        Thread update = new Thread(() -> {
            String said;
            boolean needsStudio = false;
            boolean reload = false;
            try {
                switch (RunnerUpdate.update(config, tag, pinned)) {
                    case UPDATED -> {
                        said = "Updated to " + tag + ".";
                        reload = true;
                    }
                    case UP_TO_DATE -> said = "This bot already has " + tag + ".";
                    case NEEDS_STUDIO -> {
                        said = "This update needs Studio to finish.";
                        needsStudio = true;
                    }
                    default -> said = "";
                }
            } catch (IOException | RuntimeException e) {
                said = "Couldn't update: " + rootMessage(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                said = "The update was stopped.";
            }
            String message = said;
            boolean studio = needsStudio;
            boolean thenReload = reload;
            Platform.runLater(() -> {
                if (disposed) return;   // the user left this bot: its project is no longer the open one
                header.updateSaid(message, studio);
                if (thenReload) eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
            });
        }, "runner-update");
        update.setDaemon(true);
        update.start();
    }

    /**
     * "Improve this bot" — the one action here that changes what the project is. It drops the local opt-in
     * marker, commits the as-installed state so there is a restore point, and reloads: the project then opens
     * as the editor, because {@link ProjectMode} now says it is yours.
     */
    private void switchToEditorMode() {
        try {
            ProjectMode.switchToEditor(config.projectPath());
        } catch (IOException ex) {
            ThemedWindows.alert(Alert.AlertType.ERROR, "Couldn't switch to Editor mode: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
            return;
        }
        // Daemon: a git commit that hangs must not keep the JVM alive after the user closes the window.
        Thread commit = new Thread(() -> {
            Checkpoints.take(config.projectPath(), VersionOrigin.SAFETY,
                    "Start editing (switched from Reader mode)");
            Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent()));
        }, "reader-to-editor");
        commit.setDaemon(true);
        commit.start();
    }

    // =========================================================================
    // BODY — the settings the plugins expose
    // =========================================================================

    private Node body() {
        reload();
        VBox column = new VBox(18);
        column.setPadding(new Insets(18));
        column.setMaxWidth(CONTENT_MAX_WIDTH);
        if (!dropped.isEmpty()) {
            // Said once: the values are forgotten as they are said (applyKeptValues).
            column.getChildren().add(hint(about.name() + about.version().map(v -> " " + v).orElse("")
                    + " no longer has: " + String.join(", ", dropped) + "."));
        }
        column.getChildren().add(settingsSection());

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("transparent-scroll");
        bodyScroll = scroll;
        return scroll;
    }

    /**
     * Scrolls the body so {@code target} is at the top of the viewport.
     *
     * <p>Measured in the content's own coordinates rather than the section's, because a category card is
     * nested three deep and its position within its parent says nothing about where it is in the column.
     */
    private void scrollTo(Node target) {
        if (bodyScroll == null) return;
        Node content = bodyScroll.getContent();
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewHeight = bodyScroll.getViewportBounds().getHeight();
        if (contentHeight <= viewHeight) return;
        double y = content.sceneToLocal(target.localToScene(0, 0)).getY();
        bodyScroll.setVvalue(Math.max(0, Math.min(1, y / (contentHeight - viewHeight))));
    }

    /**
     * The public parameters of the project — the bot's own {@code @Param} fields and the loaded plugins'
     * rows, in section order.
     *
     * <p>Read once, when the window is built: the Runner is not an editor, so nothing here changes a
     * declaration and the only writes are values, which go back to their owner on Run.
     *
     * <p><b>A parameter the author kept to themselves is never read at all</b> — {@code isPublic()} is
     * checked here rather than at the card, so an editor-only one has no widget, no reader and nothing that
     * could send a value for it. A user's window must not be able to change what it cannot see.
     *
     * <p><b>And neither is one the editor itself cannot rewrite.</b> A hand-written initialiser is shown
     * read-only in the Parameters window, where its author can read the reason; here there is no author and
     * no reason to show, so it is simply not one of this bot's settings.
     */
    private void reload() {
        rows.clear();
        HostParamScope scope = HostParamScope.over(HostPluginValues.live());
        Set<String> declared = new HashSet<>();
        for (JavaParameter entry : JavaParameters.scan(config, state)) {
            declared.add(entry.qualified());
            // A plugin's conditional parameter only while it applies (ManagedParam.ConditionStep.when, 2026-10-12).
            if (entry.row().isPublic() && entry.editable() && scope.shown(entry)) rows.add(entry);
        }
        records = BotRecords.scan(config, state, PluginHost.grammar());
        applyKeptValues(declared);
    }

    /**
     * Writes the runner's kept values that the source lacks — an update brought the author's defaults back, or
     * this is a newer release beside the old one — and forgets, once said, those this version no longer takes.
     * A value whose field is still {@code declared} but not shown now (a conditional one, one whose plugin did
     * not load) is kept for later, untouched.
     */
    private void applyKeptValues(Set<String> declared) {
        RunnerValues.Applied applied = kept.applyTo(rows, declared, this::fits);
        applied.values().forEach((entry, source) -> JavaValue.parse(source).ifPresent(value -> {
            Optional<ParameterRow> stored = JavaParameters.setValue(config, state, entry, value);
            int at = rows.indexOf(entry);
            stored.ifPresent(row -> rows.set(at, entry.withRow(row)));
        }));
        dropped = applied.dropped();
        if (!dropped.isEmpty()) {
            kept = kept.without(dropped);
            kept.save();
        }
    }

    /** Whether {@code source} still reads as {@code entry}'s type: the grammar's, or one of the bot's records. */
    private boolean fits(JavaParameter entry, String source) {
        if (entry.form() instanceof ValueTypes.BotClass declared) {
            return records.partsOf(declared, source).isPresent();
        }
        return PluginHost.grammar().valueOf(entry.form(), source).isPresent();
    }

    // sdkPin() stood here until 2026-09-22, reading the pom so the parameter data surface could be asked at
    // the version the project pins. The rows come off the bot's own source now, which no version qualifies.

    /**
     * The rows under their category headings. The grouping is the whole reason a row carries a category — the
     * bot's user reads "Mining" and "General", not one flat list in which two activities' delays are told
     * apart only by a prefix in their name.
     *
     * <p>The categories are merged across sections rather than nested under one heading per plugin: a
     * category is a statement about what a parameter configures, and a user of a bot should not have to hold
     * the plugin architecture in their head to find a delay.
     *
     * <p>An empty section says so rather than quietly rendering nothing: "no settings" is a fact about this
     * bot, not a sign the window failed to load.
     */
    private Node settingsSection() {
        Map<String, List<JavaParameter>> byCategory = byCategory();
        VBox groups = new VBox(18);
        if (byCategory.isEmpty()) {
            groups.getChildren().add(hint("This bot has no settings for you to change."));
        }
        byCategory.forEach((category, group) -> groups.getChildren().add(categoryCard(category, group)));

        Node index = categoryIndex(byCategory);
        return section("Settings", null, index == null ? groups : new VBox(12, index, groups));
    }

    /** The rows filed under each category, in the order the plugins declared them. */
    private Map<String, List<JavaParameter>> byCategory() {
        Map<String, List<JavaParameter>> byCategory = new LinkedHashMap<>();
        for (JavaParameter entry : rows) {
            byCategory.computeIfAbsent(entry.row().categoryOrGeneral(), key -> new ArrayList<>()).add(entry);
        }
        return byCategory;
    }

    /**
     * A wrapping row of one chip per category, each jumping to its card.
     *
     * <p>A bot with a dozen categories is a page of scrolling, and the thing being looked for is nearly always
     * known by name before the scrolling starts. The chips are built from the same map the cards are, so the
     * index cannot offer a category that isn't below it — and it is left out entirely below two categories,
     * where a jump list is longer than the thing it indexes.
     */
    private Node categoryIndex(Map<String, List<JavaParameter>> byTag) {
        if (byTag.size() < 3) return null;
        FlowPane chips = new FlowPane(6, 6);
        byTag.forEach((tag, group) -> {
            Button chip = new Button(tag + " (" + group.size() + ")");
            chip.getStyleClass().add("runner-category-chip");
            chip.setOnAction(e -> {
                Node card = categoryCards.get(tag);
                if (card != null) scrollTo(card);
            });
            chips.getChildren().add(chip);
        });
        return chips;
    }

    /**
     * One tag as a titled block of setting cards.
     *
     * <p>The headings used to be a bare {@link Label} over a flat column, and at twenty settings the
     * categories stopped reading as categories — everything was the same weight in one long list, and the
     * window's width went unused no matter how wide it was pulled. A titled card with a rule under it and a
     * reflowing grid inside says where a group starts and ends without anybody having to count rows.
     */
    private Node categoryCard(String tag, List<JavaParameter> group) {
        Label heading = new Label(tag);
        heading.getStyleClass().add(StyleClasses.DIALOG_SUBHEADING);
        Label count = hint(group.size() == 1 ? "1 setting" : group.size() + " settings");

        HBox title = new HBox(8, heading, count);
        title.setAlignment(Pos.BASELINE_LEFT);

        TilePane tiles = new TilePane(12, 12);
        tiles.setPrefColumns(1);
        // One column when the window is narrow, several when it is wide: the tile is a fixed width and the
        // pane wraps, so the reflow is the layout's own doing and needs no width listener.
        tiles.setPrefTileWidth(TILE_WIDTH);
        tiles.setTileAlignment(Pos.TOP_LEFT);
        for (JavaParameter entry : group) tiles.getChildren().add(paramCard(entry));

        VBox card = new VBox(8, title, new Separator(), tiles);
        card.getStyleClass().add("runner-category");
        categoryCards.put(tag, card);
        return card;
    }

    /**
     * One setting as a card: what it is called, <b>what kind of value it is</b>, its editor, and the author's
     * note underneath.
     *
     * <p>The type badge is there because a value with no unit or shape stated is a guess — a bare {@code 30}
     * beside "Delay" could be seconds or milliseconds, and "Region" could be a name or four numbers. The
     * badge says which, in the same words the author picked the type with.
     */
    private Node paramCard(JavaParameter entry) {
        ParameterRow v = entry.row();
        Label name = new Label(v.displayLabel());
        name.getStyleClass().add("runner-setting-name");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        // The type as the field declares it, which since 2026-09-20 is the only name a form has. It was
        // "One of Image template" while a shape carried the prose; a badge naming the Java is the same rule
        // the picker's button follows, and it is what the user reads in their own file.
        Label badge = new Label(ValueTypes.sourceName(entry.form()));
        badge.getStyleClass().add("runner-type-badge");
        badge.setWrapText(true);
        // The badge keeps the width it asks for and the name wraps into what is left. The other way round —
        // the name growing and the badge shrinking — is what put an ellipsis through "One of Image template"
        // and, on a long name, through the name as well: a card is 300px wide and a Label in a full row
        // truncates rather than wrapping unless it is told which of the two gives way.
        badge.setMinWidth(Region.USE_PREF_SIZE);

        HBox header = new HBox(6, name, badge);
        header.setAlignment(Pos.TOP_LEFT);

        Node widget = ParamValueWidgets.build(entry.className(), v, entry.form(), config, records, valueEditors);
        if (widget instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);

        VBox card = new VBox(6, header, widget);
        if (!v.description().isBlank()) card.getChildren().add(hint(v.description()));
        card.getStyleClass().add("runner-setting-card");
        card.setPrefWidth(TILE_WIDTH);
        card.setMinHeight(Region.USE_PREF_SIZE);   // a wrapped name makes the card taller, never shorter
        return card;
    }

    private static Node section(String title, String hintText, Node content) {
        Label heading = new Label(title);
        heading.getStyleClass().add(StyleClasses.DIALOG_HEADING);
        VBox box = new VBox(8, heading);
        if (hintText != null) box.getChildren().add(hint(hintText));
        box.getChildren().add(content);
        box.getStyleClass().add("runner-section");
        return box;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint-text");
        label.setWrapText(true);
        return label;
    }

    // =========================================================================
    // RUN BAR
    // =========================================================================

    /** The run's last message and its log. Start and Stop are in the header since 2026-10-25. */
    private Node runBar() {
        status = new Label("");
        status.getStyleClass().add("dialog-status");

        HBox buttons = new HBox(8, status);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8, 12, 8, 12));

        log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(8);
        log.getStyleClass().add("console-area");

        VBox bar = new VBox(buttons, log);
        bar.getStyleClass().add("runner-runbar");
        return bar;
    }

    /**
     * Hands every changed value to the plugin that owns it, then runs.
     *
     * <p>The save is not optional and not a separate button: a tick box that does nothing until you find a
     * Save you didn't know about is the bug this ordering removes. The run happens after the writes because a
     * bot reads its parameters off the classpath at startup — starting first would run the previous answers.
     *
     * <p><b>Each row is written into the bot's own source one value at a time</b>, rather than by this
     * window rewriting one file. That is what takes the Runner out of the two-writer hazard entirely: nothing
     * here holds a document that another window could have moved underneath it, and a plugin that is not
     * installed cannot lose its data because nothing here ever reads it.
     */
    private void run() {
        status.setText("Saving your choices…");
        try {
            flushValues();
        } catch (RuntimeException e) {
            status.setText("Couldn't save your choices: " + rootMessage(e));
            return;
        }
        status.setText("Starting…");
        // Start stays enabled until ProgramStarted: a run that is refused (a compile error, an already-running
        // bot) reports on the status line and never sends ProgramStarted/Stopped.
        eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
    }

    /**
     * Sends each widget's value to the section that owns the row it was built from.
     *
     * <p>Only what changed: a row whose widget reads back exactly what it was seeded with is not written, so
     * pressing Run twice writes nothing the second time. The answer is the row <em>as stored</em> — clamped,
     * canonicalised, pruned to the choices still on offer by the owning plugin — and it is kept, so a second
     * Run compares against what the bot will actually get rather than against what was typed.
     *
     * <p>Each value written is kept for the runner too ({@link RunnerValues}), so the next release of the bot
     * gets it again.
     */
    private void flushValues() {
        RunnerValues was = kept;
        for (ParamValueWidgets.ValueEditor editor : valueEditors) {
            for (int i = 0; i < rows.size(); i++) {
                JavaParameter entry = rows.get(i);
                if (!editor.describes(entry.className(), entry.row().name())) continue;
                Optional<JavaValue> typed = editor.read().get();
                if (typed.isEmpty() || typed.get().source().equals(entry.row().value())) break;
                Optional<ParameterRow> stored = JavaParameters.setValue(config, state, entry, typed.get());
                int at = i;
                stored.ifPresent(row -> {
                    rows.set(at, entry.withRow(row));
                    kept = kept.with(entry.qualified(), row.value());
                });
                break;
            }
        }
        if (kept != was) kept.save();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    // =========================================================================
    // EVENTS
    // =========================================================================

    private void subscribe() {
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class,
                e -> log.appendText(e.text()), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.OutputClearedEvent.class,
                e -> log.clear(), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.StatusMessageEvent.class,
                e -> status.setText(e.message()), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class,
                e -> running(true), true));
        subscriptions.add(eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class,
                e -> running(false), true));
    }

    private void running(boolean isRunning) {
        header.running(isRunning);
    }
}
