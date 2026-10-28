package com.botmaker.studio.ui.app;

import com.botmaker.studio.migrate.Broken;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.ReleasedPlugins;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.LocalBuilds;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import com.botmaker.studio.services.upgrade.PluginHolders;
import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import com.botmaker.studio.services.upgrade.ProjectUpgrade;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The <b>Installed</b> tab of <b>Project ▸ Plugins &amp; Libraries…</b> — every plugin this project installs,
 * the version it is on, the jar Studio loaded, and one button that moves it.
 *
 * <p>This was the <b>Upgrade…</b> window until 2026-09-29; it is a tab of {@link PluginsWindow} now, beside
 * Browse and Libraries. The SDK appears here as an ordinary row, and this is the only place any plugin's
 * version is changed — Libraries holds plugin rows back and Browse offers no Remove, so no plugin moves
 * without its repair.
 *
 * <h2>One button per row (2026-10-06)</h2>
 *
 * <p>A row opens on the version it is <em>on</em>. Its button says what one click does: <i>Upgrade to X</i>
 * when a newer release is known, <i>Switch to X</i> once another version is picked in the menu (a downgrade is
 * the same operation), and nothing when there is nothing to do. <i>Upgrade all</i> moves every row that has an
 * upgrade, in one pass. Until this date every row was seeded with its newest version, each pick ran a Check
 * whose report filled the tab with prose, and a separate <i>Snapshot, repair &amp; switch</i> applied whatever
 * was picked — so upgrading one plugin meant un-picking all the others. A pass takes a snapshot and carries the
 * bot over with its {@code @Migrate} rules, a default rule seeded and applied for each broken element none names
 * (2026-10-16).
 *
 * <p>A dev build in a dev-mode project is the build being tried, so its row offers no upgrade.
 *
 * <h2>Remove is the same repair with the plugin gone</h2>
 *
 * <p>Every element of the plugin the bot uses is broken, and each takes the bot's rule or a seeded default; a
 * use no rule can stand at — a type the bot writes <em>down</em> — is left as written, marked and listed.
 * {@link RemovalSheet} says so before anything is written.
 *
 * <p><b>Install has no repair</b>, and needs none: it is {@link BrowsePluginsTab}'s, and this tab's <i>Add a
 * plugin…</i> switches to it.
 *
 * <h2>One pass, not one pass per row</h2>
 *
 * <p>{@link ProjectUpgrade} takes one snapshot, repairs the code against every row's target at once and writes
 * the pom once: a project sitting on plugin A's new version with plugin B's old source compiles against neither.
 */
public final class InstalledPluginsTab {

    /** The state cell's base style class; {@code CHIP + "-ok"} and friends carry the colour. */
    private static final String CHIP = "upgrade-chip";

    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    /** The window's shared {@code ~/.m2} scan, asked only in dev mode. */
    private final Supplier<CompletableFuture<List<LocalBuilds.Build>>> localBuilds;

    private final GridPane table = new GridPane();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button upgradeAll = new Button("Upgrade all");
    private final List<Row> rows = new ArrayList<>();

    /** The success block, hidden until there is one. See {@link #showResult}. */
    private final VBox resultBox = new VBox(6);
    private final Label resultText = new Label();
    private final Button openReview = new Button("Open Review tab");

    private final Runnable onAddPlugin;
    private final Runnable onOpenReview;
    private final Runnable onChanged;
    private final Consumer<Class<?>> onMigrate;
    private final Runnable onMigrateRules;
    private final VBox root = new VBox(12);

    /** Shown while a row pins a dev build Studio refused; see {@link #showDevBar}. */
    private final Label devText = new Label();
    private final Button useDevMode = new Button("Use dev mode");
    private final HBox devBar = new HBox(10, devText, useDevMode);

    /** Whether the project is in dev mode, read on every {@link #load}: its dev rows load and are not refused. */
    private boolean devMode;

    /** In dev mode, the {@code ~/.m2} build of each coordinate that has one: {@code group:artifact → version}. */
    private Map<String, String> localVersions = Map.of();

    /** Bumped by every {@link #load}, so a slower earlier load never draws over a later one. */
    private int generation;

    /** True while a pass or a removal runs: every row's button waits for it. */
    private boolean busy;

    /**
     * @param onAddPlugin  <i>Add a plugin…</i>: the window switches to Browse
     * @param onOpenReview the result block's <i>Open Review tab</i>; the tab is the shell's, so the shell
     *                     supplies the verb, and the window closes behind it
     * @param onChanged    after a pass or a removal wrote the pom, so the window's other tabs read it again
     * @param onMigrate    <i>Migrate first</i>: the migration window on a type the move would strand, which the
     *                     shell owns, so the window closes behind it
     * @param onMigrateRules <i>Migrate first</i> before a move that breaks what no rule covers: Migrate, once the
     *                     pom moved, the window closing behind it
     */
    InstalledPluginsTab(ProjectConfig config, ProjectState state, LibraryService libraryService,
                        PluginRegistry registry, JitPackSearch jitpack,
                        Supplier<CompletableFuture<List<LocalBuilds.Build>>> localBuilds,
                        Runnable onAddPlugin, Runnable onOpenReview, Runnable onChanged,
                        Consumer<Class<?>> onMigrate, Runnable onMigrateRules) {
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
        this.localBuilds = localBuilds;
        this.onAddPlugin = onAddPlugin;
        this.onOpenReview = onOpenReview;
        this.onChanged = onChanged;
        this.onMigrate = onMigrate;
        this.onMigrateRules = onMigrateRules;
        build();
    }

    /** The tab's content. */
    Node node() {
        return root;
    }

    /** Reads the rows again — the pom changed, or the plugins were reloaded. */
    void reload() {
        progress.setVisible(true);
        load();
    }

    private void build() {
        progress.setPrefSize(18, 18);
        progress.setVisible(true);

        table.setHgap(10);
        table.setVgap(6);
        ColumnConstraints name = new ColumnConstraints();
        name.setHgrow(Priority.ALWAYS);
        table.getColumnConstraints().add(name);
        ScrollPane tableScroll = new ScrollPane(table);
        tableScroll.setFitToWidth(true);
        VBox.setVgrow(tableScroll, Priority.ALWAYS);

        Label hint = new Label("Every plugin this project uses. A move takes a snapshot first and repairs "
                + "this bot's calls into the plugin.");
        hint.setWrapText(true);
        hint.getStyleClass().add("sdk-upgrade-empty");

        resultText.setWrapText(true);
        openReview.setOnAction(e -> onOpenReview.run());
        resultBox.getStyleClass().add("sdk-upgrade-card");
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        devText.setWrapText(true);
        HBox.setHgrow(devText, Priority.ALWAYS);
        useDevMode.setTooltip(new Tooltip(PluginsWindow.DEV_MODE_TIP));
        useDevMode.setOnAction(e -> {
            useDevMode.setDisable(true);
            libraryService.setDevMode(true).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                useDevMode.setDisable(false);
                if (failure != null) {
                    status("Could not turn on dev mode: " + failure.getMessage());
                    return;
                }
                reload();
                onChanged.run();
            }));
        });
        devBar.setAlignment(Pos.CENTER_LEFT);
        devBar.getStyleClass().add("sdk-upgrade-card");
        showDevBar(0);

        statusLabel.setWrapText(true);
        root.setPadding(new Insets(12, 0, 0, 0));
        root.getChildren().addAll(new HBox(8, hint, progress), devBar, tableScroll, resultBox, statusLabel,
                buttonBar());
        load();
    }

    /** The dev-build line above the table, shown while any row pins a build Studio will not load. */
    private void showDevBar(int devRows) {
        devText.setText(devText(devRows));
        devBar.setVisible(devRows > 0);
        devBar.setManaged(devRows > 0);
    }

    /** What the dev-build line says, or "" when no row pins a dev build. */
    static String devText(int devRows) {
        if (devRows == 0) return "";
        return (devRows == 1 ? "A plugin is" : devRows + " plugins are") + " pinned to a dev build, which Studio "
                + "does not load outside dev mode. Switch " + (devRows == 1 ? "it" : "them") + " to a release on "
                + (devRows == 1 ? "its row" : "their rows") + ", or use dev mode on this computer.";
    }

    private Node buttonBar() {
        // Install has no repair and needs none, so it is a door to the catalogue rather than a control here.
        Button add = new Button("Add a plugin…");
        add.setOnAction(e -> onAddPlugin.run());

        upgradeAll.setDisable(true);
        upgradeAll.setTooltip(new Tooltip("Every row with a newer release, in one pass: a snapshot first, then "
                + "this bot's calls repaired."));
        upgradeAll.setOnAction(e -> runPass(rows.stream().filter(r -> !r.upgrade().isEmpty())
                .map(r -> r.asRow(r.upgrade())).toList()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, add, spacer, upgradeAll);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    /** Repaints every row's button and <i>Upgrade all</i>, after a version list arrived or a pass ended. */
    private void refreshButtons() {
        rows.forEach(Row::refreshAction);
        long upgradable = rows.stream().filter(r -> !r.upgrade().isEmpty()).count();
        upgradeAll.setText(upgradable > 1 ? "Upgrade all (" + upgradable + ")" : "Upgrade all");
        upgradeAll.setDisable(busy || upgradable == 0);
    }

    // -------------------------------------------------------------------------
    // Loading the rows
    // -------------------------------------------------------------------------

    /**
     * Builds the table off the FX thread.
     *
     * <p>Everything slow happens here and everything slow degrades: the registry may be unreachable (rows
     * then read as unlisted, with no version to move to until JitPack answers), a jar may never have been
     * downloaded (that coordinate is simply not a row), and the clash scan reads every installed jar. What
     * the window must never do is show an empty table because a network call failed.
     */
    private void load() {
        int asked = ++generation;
        devMode = libraryService.devMode();
        // In dev mode each row also offers this computer's own build of it (LocalBuilds), e.g. the SDK the
        // umbrella just installed at its main SNAPSHOT. The scan is the window's, off the FX thread.
        CompletableFuture<List<LocalBuilds.Build>> scan = devMode
                ? localBuilds.get() : CompletableFuture.completedFuture(List.of());
        status("Reading this project's plugins…");
        // Async: with no registry configured both halves may already be done, and this reads jars.
        registry.browse().thenCombineAsync(scan, (entries, builds) -> {
            List<UserLibrary> declaredLibraries = libraryService.declaredLibraries();
            List<InstalledPlugin> found = InstalledPlugin.of(
                    declaredLibraries, entries, InstalledPlugin.jarDeclaresPlugin(config.projectPath()));
            List<PluginHost.LoadedJar> loaded = PluginHost.loaded();
            List<Declared> declared = declaredOnce(found, loaded);
            List<PluginHost.LoadedJar> bundled = bundled(declaredLibraries, loaded);
            Map<String, String> local = new java.util.HashMap<>();
            builds.forEach(build -> local.put(build.coordinate(), build.version()));
            Platform.runLater(() -> {
                if (asked != generation) return;        // a later load (a dev-mode switch) owns the table
                localVersions = Map.copyOf(local);
                render(declared, bundled);
            });
            return null;
        }).exceptionally(error -> {
            Platform.runLater(() -> {
                progress.setVisible(false);
                status("Could not read this project's plugins: " + error.getMessage());
            });
            return null;
        });
    }

    /**
     * A plugin the pom declares, once: {@code twins} are the other copies of it the pom also names, under
     * BotMaker's other groupId (2026-10-06). Studio loads {@code row}'s; the twins are offered for removal.
     */
    record Declared(InstalledPlugin row, List<InstalledPlugin> twins) {
        Declared {
            twins = List.copyOf(twins);
        }
    }

    /** {@code found} as one entry per plugin, the copy kept being the one Studio {@code loaded}. */
    static List<Declared> declaredOnce(List<InstalledPlugin> found, List<PluginHost.LoadedJar> loaded) {
        List<Declared> out = new ArrayList<>();
        for (List<InstalledPlugin> group : InstalledPlugin.sameArtifactGroups(found)) {
            InstalledPlugin kept = InstalledPlugin.keeper(group, row -> loadedFrom(row, loaded).isPresent()
                    && group.size() > 1);
            out.add(new Declared(kept, group.stream().filter(row -> row != kept).toList()));
        }
        return List.copyOf(out);
    }

    /** The loaded plugin whose jar is exactly {@code row}'s coordinate: its groupId too, not the other one. */
    static Optional<PluginHost.LoadedJar> loadedFrom(InstalledPlugin row, List<PluginHost.LoadedJar> loaded) {
        String group = row.artifact().groupId();
        return loaded.stream()
                .filter(l -> l.isOf(group, row.artifact().artifactId()))
                .filter(l -> l.groupId().isEmpty() || l.groupId().equals(group))
                .findFirst();
    }

    /**
     * The loaded plugins whose jar the pom does not declare: each came with another plugin (plugin-basics
     * with the SDK). Shown read-only — its version is Maven's mediation, not this pom's to pin.
     */
    static List<PluginHost.LoadedJar> bundled(List<UserLibrary> declared, List<PluginHost.LoadedJar> loaded) {
        return loaded.stream()
                .filter(l -> declared.stream().noneMatch(lib -> l.isOf(lib.groupId(), lib.artifactId())))
                .toList();
    }

    /** What the Loaded cell says: the jar's version, marked when it is a dev build, or that nothing loaded. */
    static String loadedText(Optional<PluginHost.LoadedJar> loaded) {
        return loaded.map(l -> l.version() + (l.dev() ? " (dev build)" : "")).orElse("not loaded");
    }

    /** The line under a plugin the pom declares twice. */
    static String twinText(InstalledPlugin kept, List<InstalledPlugin> twins) {
        String others = String.join(", ", twins.stream()
                .map(t -> t.coordinate() + " " + t.installed()).toList());
        return "Declared twice: also as " + others + ". Studio loads " + kept.installed()
                + " only; compiling and running the bot still mixes both until one is removed.";
    }

    private void render(List<Declared> declared, List<PluginHost.LoadedJar> bundled) {
        progress.setVisible(false);
        table.getChildren().clear();
        rows.clear();
        showDevBar(0);

        if (declared.isEmpty() && bundled.isEmpty()) {
            status("");
            table.add(new Label("This project declares no plugins, so there is nothing to upgrade. "
                    + "\"Add a plugin…\" below is where one is installed."), 0, 0, 7, 1);
            refreshButtons();
            return;
        }

        status("");
        List<PluginHost.LoadedJar> loaded = PluginHost.loaded();
        int line = 0;
        table.add(heading("Plugin"), 0, line);
        table.add(heading("Installed"), 1, line);
        table.add(heading("Loaded"), 2, line);
        table.add(heading("Version"), 3, line);
        line++;

        for (Declared entry : declared) {
            Row row = new Row(entry.row(), !entry.twins().isEmpty());
            rows.add(row);
            Optional<PluginHost.LoadedJar> from = loadedFrom(entry.row(), loaded);
            PluginRowParts.LogFold log = new PluginRowParts.LogFold(from.map(PluginHost.LoadedJar::coordinate));
            table.add(PluginRowParts.named(row.name, from), 0, line);
            table.add(row.installed, 1, line);
            table.add(loadedCell(from, entry.row().artifact().artifactId()), 2, line);
            table.add(row.versions, 3, line);
            table.add(row.action, 4, line);
            table.add(row.remove, 5, line);
            table.add(log.button, 6, line);
            line++;
            // One line for the fold and the verdict: a closed fold alone on a line would still cost a gap.
            table.add(new VBox(4, log.fold, row.verdict), 0, line, 7, 1);
            line++;
            row.loadVersions();
            if (!entry.twins().isEmpty()) table.add(twinBar(entry), 0, line++, 7, 1);
        }
        for (PluginHost.LoadedJar plugin : bundled) {
            Label name = new Label(plugin.name());
            Label version = new Label(plugin.version());
            version.getStyleClass().add("sdk-upgrade-detail");
            Label how = new Label("comes with another plugin");
            how.getStyleClass().add("text-muted");
            how.setTooltip(new Tooltip("Another plugin depends on it, so its version is that plugin's to "
                    + "decide. Change the plugin that brings it instead."));
            PluginRowParts.LogFold log = new PluginRowParts.LogFold(Optional.of(plugin.coordinate()));
            table.add(PluginRowParts.named(name, Optional.of(plugin)), 0, line);
            table.add(version, 1, line);
            table.add(loadedCell(Optional.of(plugin), ""), 2, line);
            table.add(how, 3, line, 3, 1);
            table.add(log.button, 6, line);
            line++;
            table.add(log.fold, 0, line++, 7, 1);
        }
        showDevBar((int) rows.stream().filter(Row::refused).count());
        refreshButtons();
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    /** The Loaded cell, its tooltip saying why when nothing loaded. */
    private static Label loadedCell(Optional<PluginHost.LoadedJar> loaded, String artifactId) {
        Label cell = new Label(loadedText(loaded));
        cell.getStyleClass().add("sdk-upgrade-detail");
        if (loaded.isEmpty()) {
            String why = PluginHost.failures().stream()
                    .filter(f -> !artifactId.isEmpty() && f.provider().contains(artifactId))
                    .map(f -> f.cause().getMessage()).filter(m -> m != null && !m.isBlank())
                    .findFirst().orElse("Studio did not load it. Reload in this window says why.");
            cell.setTooltip(new Tooltip(why));
        } else if (loaded.get().dev()) {
            cell.setTooltip(new Tooltip("A build only this computer has, loaded because this project is in dev mode."));
        }
        return cell;
    }

    /** The fix under a plugin declared twice: keep one copy, a pom edit — the plugin itself stays. */
    private Node twinBar(Declared entry) {
        Label text = new Label(twinText(entry.row(), entry.twins()));
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox bar = new HBox(8, text);
        List<InstalledPlugin> copies = new ArrayList<>(List.of(entry.row()));
        copies.addAll(entry.twins());
        for (InstalledPlugin keep : copies) {
            Button button = new Button("Keep " + keep.installed());
            button.setTooltip(new Tooltip("Remove every other copy from the pom: "
                    + String.join(", ", copies.stream().filter(c -> c != keep)
                    .map(c -> c.coordinate() + " " + c.installed()).toList())));
            button.setOnAction(e -> keepOnly(keep, copies, bar));
            bar.getChildren().add(button);
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("sdk-upgrade-card");
        return bar;
    }

    private void keepOnly(InstalledPlugin keep, List<InstalledPlugin> copies, Node bar) {
        bar.setDisable(true);
        status("Removing the other copies of " + keep.displayName() + "…");
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        for (InstalledPlugin copy : copies) {
            if (copy == keep) continue;
            // List.of(): its editor dependencies are the kept copy's too.
            done = done.thenCompose(ignored -> libraryService.removePlugin(
                    copy.artifact().groupId(), copy.artifact().artifactId(), List.of()));
        }
        done.whenComplete((ignored, failure) -> Platform.runLater(() -> {
            bar.setDisable(false);
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                status("Could not remove the other copy: " + cause.getMessage());
                return;
            }
            status("Kept " + keep.coordinate() + " " + keep.installed() + ".");
            reload();
            onChanged.run();
        }));
    }

    // -------------------------------------------------------------------------
    // A row
    // -------------------------------------------------------------------------

    /**
     * What one click on a row's button does, as its label: {@code ""} when there is nothing to do.
     *
     * @param picked   the version the menu shows
     * @param newer    the upgrade on offer, {@code ""} for none
     * @param heldDev  a dev build in a dev-mode project: the build being tried, never offered an upgrade
     */
    static String actionLabel(String installed, String picked, String newer, boolean heldDev) {
        if (picked != null && !picked.isBlank() && !picked.equals(installed)) return "Switch to " + picked;
        if (heldDev || newer.isBlank() || newer.equals(installed)) return "";
        // From a dev build to a release is not "up": the release may well be older than the build.
        return (ReleasedPlugins.isDevVersion(installed) ? "Switch to " : "Upgrade to ") + newer;
    }

    /** The version a row's button moves to, {@code ""} for none — the target {@link #actionLabel} names. */
    static String actionTarget(String installed, String picked, String newer, boolean heldDev) {
        if (picked != null && !picked.isBlank() && !picked.equals(installed)) return picked;
        if (heldDev || newer.isBlank() || newer.equals(installed)) return "";
        return newer;
    }

    /** What a row's disabled button says instead. */
    static String idleLabel(boolean heldDev) {
        return heldDev ? "Dev build" : "Up to date";
    }

    /** One plugin: its version menu, its one button, its Remove. */
    private final class Row {

        private final InstalledPlugin plugin;
        private final PluginUpgradeService upgrades;
        /** Declared twice: nothing moves until one copy is kept, or the repair would be about the wrong pom. */
        private final boolean twinned;

        private final Label name;
        private final Label installed;
        private final ComboBox<String> versions = new ComboBox<>();
        private final Button action = new Button();
        private final Button remove = new Button("Remove…");
        private final Label verdict = new Label();

        Row(InstalledPlugin plugin, boolean twinned) {
            this.plugin = plugin;
            this.twinned = twinned;
            this.upgrades = new PluginUpgradeService(config, state, libraryService, jitpack, plugin.artifact());

            name = new Label(plugin.displayName());
            // Resolved: a template pins its SDK as ${botmaker.sdk.version}, and the pom's text is not a version.
            installed = new Label(upgrades.currentVersion());
            installed.getStyleClass().add("sdk-upgrade-detail");

            versions.setPrefWidth(190);
            versions.setPromptText("loading versions…");
            versions.setDisable(true);
            versions.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> refreshButtons());

            action.setOnAction(e -> {
                String target = target();
                if (!target.isEmpty()) runPass(List.of(asRow(target)));
            });
            remove.setOnAction(e -> runRemoval());
            verdict.setWrapText(true);
            if (twinned) {
                // Removing or moving one copy would repair the bot as if the other were not still declared:
                // its calls rewritten for a plugin the next bind loads again.
                for (Control control : List.<Control>of(versions, action, remove)) control.setDisable(true);
                remove.setTooltip(new Tooltip("Keep one copy first, below."));
            }
        }

        /** Whether this row pins a build only this machine has. */
        boolean isDev() {
            return ReleasedPlugins.isDevVersion(upgrades.currentVersion());
        }

        /** Whether this row pins a dev build {@code PluginHost.bind} refused: one outside dev mode. */
        boolean refused() {
            return isDev() && !devMode;
        }

        /** A dev build in a dev-mode project: the one being tried, offered no upgrade. */
        boolean heldDev() {
            return isDev() && devMode;
        }

        /**
         * The upgrade on offer, {@code ""} for none: for a refused dev build, a release; else JitPack's newest
         * build ({@code available}, since 2026-10-12 — the registry's verified one before), when newer.
         */
        String newer() {
            if (twinned || heldDev()) return "";
            if (refused()) return released();
            String best = PluginUpgradeService.recommended(upgrades.currentVersion(), plugin.available());
            return best.equals(upgrades.currentVersion()) ? "" : best;
        }

        /**
         * What <i>Upgrade all</i> moves this row to, {@code ""} for nothing: the newest build alone, never a
         * pick (a refused dev row's fallback included).
         */
        String upgrade() {
            if (busy || versions.isDisable()) return "";
            String next = newer();
            return next.equals(plugin.available()) ? next : "";
        }

        /** What this row's button moves to now. */
        String target() {
            return actionTarget(upgrades.currentVersion(), versions.getValue(), newer(), heldDev());
        }

        /** The release a dev row moves to: the newest build the registry read found, else the menu's, else "". */
        String released() {
            if (!plugin.available().isBlank()) return plugin.available();
            return versions.getItems().stream().filter(v -> !ReleasedPlugins.isDevVersion(v)).findFirst().orElse("");
        }

        void refreshAction() {
            String label = actionLabel(upgrades.currentVersion(), versions.getValue(), newer(), heldDev());
            action.setText(label.isEmpty() ? idleLabel(heldDev()) : label);
            action.setDisable(twinned || busy || label.isEmpty());
            remove.setDisable(twinned || busy);
        }

        /**
         * Fills the menu, opened on the installed version.
         *
         * <p>Seeded with what is already known — the installed version, the newest build, the
         * local build — so a row is usable before JitPack answers and stays usable if it never does. The list
         * is then every version JitPack can build, which is what makes a downgrade reachable. A plugin the
         * catalog lists offers the releases it approved instead, newest first, and JitPack is not asked
         * (2026-10-24): a build no check passed is not loaded on the catalog's word.
         */
        void loadVersions() {
            List<String> known = new ArrayList<>(plugin.approved());
            for (String v : List.of(upgrades.currentVersion(), plugin.available(), localVersion())) {
                if (!v.isBlank() && !known.contains(v)) known.add(v);
            }
            versions.setCellFactory(list -> new VersionCell());
            versions.setButtonCell(new VersionCell());
            versions.getItems().setAll(known);
            versions.getSelectionModel().select(upgrades.currentVersion());
            versions.setDisable(twinned);
            versions.setPromptText(null);
            markDev();
            if (!plugin.approved().isEmpty()) return;

            upgrades.availableVersions().thenAccept(fetched -> Platform.runLater(() -> {
                if (fetched.isEmpty()) return;               // offline: the seed is still a real answer
                String selected = versions.getValue();
                List<String> items = new ArrayList<>(fetched);
                for (String v : known) if (!items.contains(v)) items.add(v);
                versions.getItems().setAll(items);
                versions.getSelectionModel().select(items.contains(selected) ? selected : upgrades.currentVersion());
                refreshButtons();
            }));
        }

        /** Says on the row that Studio did not load it. */
        private void markDev() {
            if (refused()) chip("dev build — not loaded", "partial");
        }

        /** A version, with which one is installed, which one is local and which one is the newest build. */
        private final class VersionCell extends javafx.scene.control.ListCell<String> {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String tag = item.equals(installed.getText())
                        ? (isDev() ? "  (installed, dev build)" : "  (installed)")
                        : item.equals(localVersion()) ? "  (local build)"
                        : item.equals(plugin.available()) ? "  (newest)" : "";
                setText(item + tag);
            }
        }

        /** This computer's {@code ~/.m2} build of the row's coordinate in dev mode, else {@code ""}. */
        String localVersion() {
            return localVersions.getOrDefault(plugin.artifact().groupId() + ":" + plugin.artifact().artifactId(), "");
        }

        /** Repaints this row's state line. One class per state, so both themes are the stylesheet's job. */
        private void chip(String text, String state) {
            verdict.getStyleClass().removeIf(c -> c.startsWith(CHIP));
            if (!text.isEmpty()) verdict.getStyleClass().addAll(CHIP, CHIP + "-" + state);
            verdict.setText(text);
        }

        ProjectUpgrade.Row asRow(String target) {
            return new ProjectUpgrade.Row(upgrades, target);
        }

        /**
         * Reads what removing the plugin would break, then asks ({@link RemovalSheet}) before anything is
         * written: a use no rule covers takes a seeded default, a value or nothing.
         */
        void runRemoval() {
            busy = true;
            refreshButtons();
            progress.setVisible(true);
            chip("reading…", "checking");
            status("Reading what removing " + plugin.displayName() + " would change…");

            Thread worker = new Thread(() -> {
                Report report;
                PluginHolders holders;
                try {
                    report = upgrades.removal(plugin.editorDependencies());
                    holders = upgrades.holders();
                } catch (RuntimeException e) {
                    String message = e.getMessage();
                    Platform.runLater(() -> {
                        endBusy();
                        chip("could not read it", "blocked");
                        status("Reading " + plugin.displayName() + " failed: " + message);
                    });
                    return;
                }
                Platform.runLater(() -> {
                    endBusy();
                    chip("", "none");
                    status("");
                    Optional<Boolean> answer = RemovalSheet.ask(root.getScene() == null ? null
                            : root.getScene().getWindow(), plugin.displayName(), report, holders);
                    answer.ifPresent(deleteFiles -> remove(deleteFiles ? holders : PluginHolders.NONE));
                });
            }, "plugin-removal-check");
            worker.setDaemon(true);
            worker.start();
        }

        private void remove(PluginHolders holders) {
            busy = true;
            refreshButtons();
            progress.setVisible(true);
            status("Saving a version, migrating your code and removing " + plugin.displayName() + "…");

            upgrades.remove(plugin.editorDependencies(), holders)
                    .whenComplete((repaired, error) -> Platform.runLater(() -> {
                        endBusy();
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            status("");
                            ThemedWindows.alert(Alert.AlertType.ERROR,
                                    "The removal did not run:\n\n" + cause.getMessage()).showAndWait();
                            return;
                        }
                        status("");
                        holders.forget(config, state);
                        showResult(removalSummary(plugin.displayName(), repaired.files(), repaired.uses(),
                                repaired.leftAsWritten(), repaired.deleted()), repaired.files() > 0);
                        reload();
                        onChanged.run();
                    }));
        }
    }

    private void endBusy() {
        busy = false;
        progress.setVisible(false);
        refreshButtons();
    }

    /** A report's verdict in a few words; what the assistant's removal preview opens with ({@code StudioBridge}). */
    static String chipText(Report r) {
        if (r.broken().isEmpty()) return r.isIncomplete() ? "partly checked" : "nothing breaks";
        String broken = r.broken().size() + " broken" + (r.covered().isEmpty() ? ""
                : ", " + r.covered().size() + " with a rule");
        return r.isIncomplete() ? broken + ", partly checked" : broken;
    }

    /**
     * The removal's own result sentence — the upgrade's {@link ProjectUpgrade.Result#summary()} for the one
     * operation that has no versions to name.
     */
    static String removalSummary(String plugin, int filesRewritten, int uses, List<String> leftForYou,
                                 String deleted) {
        String head = "Removed " + plugin + " from this project."
                + (deleted.isBlank() ? "" : " Deleted its files: " + deleted + ".");
        String left = leftForYou.isEmpty() ? "" : " " + leftForYou.size()
                + (leftForYou.size() == 1 ? " thing was" : " things were") + " left for you to finish: "
                + String.join(" ", leftForYou);
        String back = " The previous state is one restore away in the Versions tab.";
        if (filesRewritten == 0) {
            return head + (deleted.isBlank() ? " This bot called nothing in it, so only the pom changed."
                    : " Nothing else in this bot named it.") + left + back;
        }
        String files = filesRewritten + " file" + (filesRewritten == 1 ? "" : "s");
        String what = uses == 0
                ? " Rewrote " + files + "; the functions still naming it are marked for review."
                : " " + uses + " use" + (uses == 1 ? "" : "s") + " migrated in " + files
                        + " — a default rule stands where none was written, marked for review in Migrations.";
        return head + what + left + back;
    }

    /** The lines that name a plugin file about to be deleted, at most eight of them spelled out. */
    static String holderReferences(List<String> references) {
        int shown = Math.min(references.size(), 8);
        String more = references.size() > shown ? " and " + (references.size() - shown) + " more" : "";
        return "Your code still names them at " + String.join(", ", references.subList(0, shown)) + more
                + ". Those lines will not compile without the files: each function they are in is marked for "
                + "review, and anything outside a function is listed afterwards. Their imports are removed.";
    }

    /** An open set one plugin's move drops while the bot still has values in it. */
    record Dropped(String plugin, String target, MigrationSteps.Stranded set) {

        /** What the set's values are, as a sentence names them. */
        String typeName() {
            return set.type() == null ? set.id() : JavaNames.simple(set.type());
        }
    }

    /** What the user does about the dropped values. */
    enum DroppedChoice { MIGRATE_FIRST, UPGRADE_ANYWAY, CANCEL }

    /**
     * Asks before a move that removes or resets values with their set (2026-10-12): migrate them first, on the
     * version the bot has now — offered only when one of them can be — or upgrade anyway, which runs the repair
     * after the version it saves first.
     */
    private DroppedChoice askDropped(List<Dropped> dropped) {
        ButtonType migrate = new ButtonType("Migrate first", ButtonBar.ButtonData.OK_DONE);
        ButtonType anyway = new ButtonType("Upgrade anyway", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = firstMigratable(dropped).isPresent()
                ? ThemedWindows.alert(Alert.AlertType.WARNING, droppedText(dropped), migrate, anyway, cancel)
                : ThemedWindows.alert(Alert.AlertType.WARNING, droppedText(dropped), anyway, cancel);
        alert.setHeaderText("Some values would be lost");
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == cancel) return DroppedChoice.CANCEL;
        return answer.get() == migrate ? DroppedChoice.MIGRATE_FIRST : DroppedChoice.UPGRADE_ANYWAY;
    }

    /** The first set the migration window can copy, which <i>Migrate first</i> opens on. */
    static Optional<Dropped> firstMigratable(List<Dropped> dropped) {
        return dropped.stream().filter(d -> d.set().migratable()).findFirst();
    }

    /** The warning's text: each dropped set, then what each answer does. Pure, for the test. */
    static String droppedText(List<Dropped> dropped) {
        StringBuilder out = new StringBuilder();
        for (Dropped each : dropped) {
            MigrationSteps.Stranded set = each.set();
            out.append("• ").append(set.values()).append(set.values() == 1 ? " value of " : " values of ")
                    .append(each.typeName()).append(": ").append(each.plugin()).append(' ').append(each.target())
                    .append(" drops their set")
                    .append(set.migratable() ? "" : "; nothing in the version you have now can take them")
                    .append(".\n");
        }
        out.append("\nUpgrading removes or resets those values; a removed value your code names is kept, unmarked."
                + " The version saved before the upgrade still has them.");
        Optional<Dropped> first = firstMigratable(dropped);
        if (first.isPresent()) {
            out.append("\n\nMigrate first copies them to another type on the version you have now, starting with ")
                    .append(first.get().typeName())
                    .append(dropped.size() > 1 ? "; migrate the others the same way, from the Review tab or a"
                            + " value's menu, then upgrade again." : ", then upgrade again.");
        }
        return out.toString();
    }

    // -------------------------------------------------------------------------
    // A pass
    // -------------------------------------------------------------------------

    /**
     * Moves {@code moving}: reads what each move breaks, asks before seeding a default rule for any element no
     * rule covers ({@link #openSentence}), and only then runs the one pass. Cancelling writes nothing.
     */
    private void runPass(List<ProjectUpgrade.Row> moving) {
        if (moving.isEmpty()) return;
        busy = true;
        refreshButtons();
        progress.setVisible(true);
        hideResult();
        status("Reading what " + (moving.size() == 1 ? "moving to " + moving.getFirst().targetVersion()
                : "moving " + moving.size() + " plugins") + " would break…");

        CompletableFuture.supplyAsync(() -> moving.stream()
                .map(row -> row.upgrades().compare(row.targetVersion()))
                .toList()).whenComplete((reports, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        endBusy();
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        status("Could not read what the move would break: " + cause.getMessage());
                        return;
                    }
                    try {
                        List<Dropped> dropped = new ArrayList<>();
                        for (int i = 0; i < moving.size(); i++) {
                            for (MigrationSteps.Stranded set : reports.get(i).stranded()) {
                                dropped.add(new Dropped(moving.get(i).upgrades().displayName(),
                                        moving.get(i).targetVersion(), set));
                            }
                        }
                        if (!dropped.isEmpty()) {
                            DroppedChoice choice = askDropped(dropped);
                            if (choice != DroppedChoice.UPGRADE_ANYWAY) {
                                endBusy();
                                status("Nothing was changed.");
                                if (choice == DroppedChoice.MIGRATE_FIRST) {
                                    firstMigratable(dropped).ifPresent(d -> onMigrate.accept(d.set().type()));
                                }
                                return;
                            }
                        }
                        String open = openSentence(moving, reports);
                        if (!open.isEmpty()) {
                            status("");
                            ButtonType migrate = new ButtonType("Migrate first", ButtonBar.ButtonData.OK_DONE);
                            ButtonType anyway = new ButtonType("Upgrade anyway", ButtonBar.ButtonData.OTHER);
                            Optional<ButtonType> answer = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, open,
                                    migrate, anyway, ButtonType.CANCEL).showAndWait();
                            if (answer.isPresent() && answer.get() == migrate) {
                                moveOnly(moving);
                                return;
                            }
                            if (answer.isEmpty() || answer.get() != anyway) {
                                endBusy();
                                status("Nothing was changed.");
                                return;
                            }
                        }
                        apply(moving);
                    } catch (RuntimeException e) {
                        // A tab left busy keeps every button grey until the window is reopened.
                        endBusy();
                        status("The upgrade did not start: " + e.getMessage());
                    }
                }));
    }

    /**
     * What the pass does to each broken element no rule the author wrote covers, as the question before it asks —
     * {@code ""} when every one has its rule, or nothing breaks.
     */
    static String openSentence(List<ProjectUpgrade.Row> moving, List<Report> reports) {
        List<String> open = new ArrayList<>();
        for (int i = 0; i < moving.size(); i++) {
            for (Broken broken : reports.get(i).open()) {
                String uses = broken.sites().size() + (broken.sites().size() == 1 ? " use" : " uses");
                open.add(broken.element().display() + " (" + moving.get(i).upgrades().displayName() + ", " + uses
                        + ")");
            }
        }
        if (open.isEmpty()) return "";
        return "This move breaks " + open.size() + (open.size() == 1 ? " element" : " elements") + " no @Migrate"
                + " rule of yours covers: " + String.join(", ", open.stream().limit(12).toList())
                + (open.size() > 12 ? " and " + (open.size() - 12) + " more" : "") + ".\n\nMigrate first moves the"
                + " version and leaves the bot's code as it is: Migrate lists each element, you write its rule, and"
                + " Apply rewrites every use.\n\nUpgrade anyway writes a"
                + " default rule for each into Migrations — a value standing for nothing, or the call deleted —"
                + " marked for review, and applies it. A use no rule can stand at is left as written and marked."
                + "\n\nEither way a version of the bot is saved first.";
    }

    /** <i>Migrate first</i>: one snapshot and the pom moved, the code left as it is, then Migrate. */
    private void moveOnly(List<ProjectUpgrade.Row> moving) {
        progress.setVisible(true);
        status("Saving a version and moving " + (moving.size() == 1 ? "to " + moving.getFirst().targetVersion()
                : moving.size() + " plugins") + "…");
        ProjectUpgrade.moveOnly(moving, libraryService::updateVersions)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    endBusy();
                    status("");
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        ThemedWindows.alert(Alert.AlertType.ERROR,
                                "The move did not run:\n\n" + cause.getMessage()).showAndWait();
                        return;
                    }
                    reload();
                    onChanged.run();
                    onMigrateRules.run();
                }));
    }

    /** The pass itself: one snapshot, the code carried over by the bot's rules, the pom written once. */
    private void apply(List<ProjectUpgrade.Row> moving) {
        progress.setVisible(true);
        status("Saving a version, writing the fixes and moving " + (moving.size() == 1
                ? "to " + moving.getFirst().targetVersion() : moving.size() + " plugins") + "…");

        ProjectUpgrade.run(moving, libraryService::updateVersions)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    endBusy();
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        status("");
                        ThemedWindows.alert(Alert.AlertType.ERROR,
                                "The upgrade did not run:\n\n" + cause.getMessage()).showAndWait();
                        return;
                    }
                    status("");
                    // A holder the repair of the values deleted: its buffer goes, and the editor moves off it.
                    var active = state.getActiveFile();
                    result.deleted().forEach(state::removeFile);
                    if (active != null && result.deleted().contains(active.getPath())) {
                        state.setActiveFile(config.entrySourceFile());
                    }
                    showResult(result.summary(), result.touchedSources());
                    // The table is about versions that have just changed, so it is read again.
                    reload();
                    onChanged.run();
                }));
    }

    /**
     * The success block: what happened, and the one door that follows from it.
     *
     * <p>Shown instead of closing the window. The Review tab button is there only when this bot's own code
     * was rewritten, because that is the only case where there is anything to review.
     */
    private void showResult(String summary, boolean marks) {
        resultText.setText(summary);
        resultBox.getChildren().setAll(resultText);
        if (marks && onOpenReview != null) {
            HBox actions = new HBox(8, openReview);
            actions.setAlignment(Pos.CENTER_LEFT);
            resultBox.getChildren().add(actions);
        }
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private void hideResult() {
        resultBox.setVisible(false);
        resultBox.setManaged(false);
    }

    private void status(String message) {
        statusLabel.setText(message == null ? "" : message);
    }
}
