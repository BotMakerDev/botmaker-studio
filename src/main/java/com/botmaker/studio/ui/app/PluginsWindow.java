package com.botmaker.studio.ui.app;

import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.plugin.ReleasedPlugins;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.UserLibrary;
import com.botmaker.core.project.managed.ManagedRepairs;
import com.botmaker.core.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.core.services.LocalBuilds;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.core.services.upgrade.InstalledPlugin;
import com.botmaker.core.services.upgrade.PluginUpgradeService;
import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <b>Project ▸ Plugins &amp; Libraries…</b> — everything the project's pom declares, in one window.
 *
 * <p>Five Project-menu entries stood here until 2026-09-29 — Manage Libraries, Manage Plugins, Reload
 * Plugins, Upgrade… and Manage Imports — four of them about the same pom and each unaware of the others: a
 * plugin installed in one window was re-versioned in a second and reloaded from a third, and a failure to
 * load showed only in the second. They are one window now, and the tabs are its three questions:
 * <ul>
 *   <li><b>Installed</b> ({@link InstalledPluginsTab}) — what this project runs, on which version: update,
 *       downgrade and remove, each through its checked report;</li>
 *   <li><b>Browse</b> ({@link BrowsePluginsTab}) — what it could run: the registry;</li>
 *   <li><b>Libraries</b> ({@link LibrariesTab}) — its ordinary Maven dependencies, plugins held back.</li>
 * </ul>
 * Reload sits in the header because it is about all three, and so does the line naming what did not load,
 * which is the first thing a user sent here by the canvas banner needs to read. Manage Imports is deleted:
 * every edit imports what it writes, and an unused import compiles.
 *
 * <p>A pom edited outside Studio rebinds by itself ({@code LibraryService.watchPom}). Reload stays for a jar
 * whose bytes changed under the same path; it was the plugin author's button for a {@code ~/.m2} build until
 * 2026-10-03, when Studio stopped loading dev builds at all ({@code plugin/ReleasedPlugins}), and is again in
 * <b>Dev mode</b> (2026-10-06), the footer's per-project box that lets them load.
 */
public final class PluginsWindow {

    /** Which tab the window opens on. */
    public enum Section { INSTALLED, BROWSE, LIBRARIES }

    private final Window owner;
    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final PluginRegistry registry;
    private final JitPackSearch jitpack;
    private final MavenCentralSearch mavenCentral;
    private final Runnable onOpenReview;
    private final Consumer<Class<?>> onMigrate;
    private final Runnable onMigrateRules;

    private final TabPane tabs = new TabPane();
    private final Label loadProblems = new Label();
    private final Label status = new Label();
    private final CheckBox devMode = new CheckBox("Dev mode");

    /**
     * The {@code ~/.m2} scan both tabs read in dev mode, run once and shared until the next reload: it walks
     * the whole local repository and opens jars. Touched on the FX thread only.
     */
    private CompletableFuture<List<LocalBuilds.Build>> localScan;
    /** Whether a dev-mode reload's check of the bot's values runs, its dialogs included. Touched on the FX thread only. */
    private boolean checkingRepairs;
    /**
     * Each dev plugin's jar as it was when its values in a project were last checked, by project and coordinate: a
     * Reload that rebuilt nothing checks nothing, and a repair the user cancelled is not asked again until a rebuild.
     */
    private static final Map<String, String> CHECKED_JARS = new ConcurrentHashMap<>();
    private InstalledPluginsTab installed;
    private BrowsePluginsTab browse;
    private LibrariesTab libraries;
    private Stage stage;

    /**
     * @param onOpenReview raises the shell's Review tab; offered after a pass rewrote the bot's code
     * @param onMigrate    opens the shell's migration window on a type; offered before a move that strands its values
     * @param onMigrateRules opens the shell's Migrate; offered before a move that breaks what no rule covers
     */
    public PluginsWindow(Window owner, ProjectConfig config, ProjectState state, LibraryService libraryService,
                         PluginRegistry registry, JitPackSearch jitpack, MavenCentralSearch mavenCentral,
                         Runnable onOpenReview, Consumer<Class<?>> onMigrate, Runnable onMigrateRules) {
        this.owner = owner;
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
        this.mavenCentral = mavenCentral;
        this.onOpenReview = onOpenReview;
        this.onMigrate = onMigrate;
        this.onMigrateRules = onMigrateRules;
    }

    public void show(Section section) {
        StudioWindow window = StudioWindow.modal("plugins-and-libraries", "Plugins & Libraries", owner)
                .size(820, 680).minSize(620, 480);
        stage = window.stage();

        installed = new InstalledPluginsTab(config, state, libraryService, registry, jitpack, this::localBuilds,
                () -> select(Section.BROWSE),
                () -> {
                    stage.close();
                    onOpenReview.run();
                },
                this::afterInstalledChanged,
                from -> {
                    stage.close();
                    onMigrate.accept(from);
                },
                () -> {
                    stage.close();
                    onMigrateRules.run();
                });
        browse = new BrowsePluginsTab(libraryService, registry, this::localBuilds,
                () -> select(Section.INSTALLED), this::afterBrowseChanged);
        libraries = new LibrariesTab(libraryService, mavenCentral, jitpack,
                InstalledPlugin.jarDeclaresPlugin(config.projectPath()), registry, () -> select(Section.INSTALLED),
                this::afterLibrariesChanged);

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().setAll(
                new Tab("Installed", installed.node()),
                new Tab("Browse", browse.node()),
                new Tab("Libraries", libraries.node()));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        select(section);

        loadProblems.setWrapText(true);
        loadProblems.getStyleClass().add("plugins-load-problems");
        showLoadProblems();

        Button reload = new Button("Reload plugins");
        reload.setTooltip(new Tooltip("Load this project's plugin jars again, without changing pom.xml — after"
                + " mvn install rebuilt one. A dev build (-SNAPSHOT) loads only in Dev mode."));
        reload.setOnAction(e -> reload(reload, libraryService.reloadPlugins()));

        devMode.setSelected(libraryService.devMode());
        devMode.setTooltip(new Tooltip(DEV_MODE_TIP));
        devMode.setOnAction(e -> reload(devMode, libraryService.setDevMode(devMode.isSelected())));

        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        status.getStyleClass().add("sdk-upgrade-detail");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, reload, devMode, status, spacer, close);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, loadProblems, tabs, footer);
        root.setPadding(new Insets(12, 16, 16, 16));
        window.show(root);
    }

    private void select(Section section) {
        tabs.getSelectionModel().select(section.ordinal());
    }

    /** Disables {@code control} until {@code reloading} — a reload, or a dev-mode switch and its reload — ends. */
    private void reload(Control control, CompletableFuture<Void> reloading) {
        control.setDisable(true);
        status.setText("Reloading plugins…");
        reloading.whenComplete((ignored, failure) -> Platform.runLater(() -> {
            control.setDisable(false);
            devMode.setSelected(libraryService.devMode());   // what the file says, also when saving it failed
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                status.setText("Could not reload plugins: " + cause.getMessage());
                return;
            }
            // Named, not counted: a reload that found the same plugins looks exactly like one that did
            // nothing, and an author who forgot to run mvn install needs to tell those apart.
            status.setText(loadedText(PluginHost.plugins()) + devText(PluginHost.devBuilds()));
            offerRebuiltRepairs();
            showLoadProblems();
            localScan = null;       // a rebuild or a dev-mode switch changes which ~/.m2 builds are offered
            installed.reload();
            browse.reload();
            browse.refreshInstalled();
        }));
    }

    /**
     * In dev mode, after a reload (2026-11-09, the user's call): a plugin rebuilt under the same {@code -SNAPSHOT}
     * may no longer declare an id the bot marks, or take a value the bot holds, and no version moved for an upgrade
     * to repair it. Each such plugin's repair is shown, and written on Repair after a saved version; Cancel leaves the
     * bot as written. One plugin at a time, each planned after the last one's write, so no plan writes over another;
     * a jar unchanged since it was last checked is not checked again; one check runs at once.
     */
    private void offerRebuiltRepairs() {
        if (!libraryService.devMode() || checkingRepairs) return;
        checkingRepairs = true;
        Predicate<UserLibrary> isPlugin = InstalledPlugin.jarDeclaresPlugin(config.projectPath());
        CompletableFuture.supplyAsync(() -> {
            Deque<PluginUpgradeService> rebuilt = new ArrayDeque<>();
            for (UserLibrary library : libraryService.declaredLibraries()) {
                if (!isPlugin.test(library)) continue;
                PluginUpgradeService upgrades = new PluginUpgradeService(config, state, libraryService::removePlugin,
                        jitpack, library);
                if (ReleasedPlugins.isDevVersion(upgrades.currentVersion()) && !checkedAlready(upgrades)) {
                    rebuilt.add(upgrades);
                }
            }
            return rebuilt;
        }).whenComplete((rebuilt, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                checkingRepairs = false;
                say("The bot's values could not be checked: " + causeOf(failure).getMessage());
                return;
            }
            nextRepair(rebuilt, false);
        }));
    }

    /** The next rebuilt plugin's repair, planned against the bot as the last one left it; then a reload, if any wrote. */
    private void nextRepair(Deque<PluginUpgradeService> rebuilt, boolean wrote) {
        PluginUpgradeService upgrades = rebuilt.poll();
        if (upgrades == null) {
            checkingRepairs = false;
            // The bind and the tabs read the bot again; the jars are unchanged, so the check that follows is skipped.
            if (wrote) reload(devMode, libraryService.reloadPlugins());
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            PluginUpgradeService.Rebuilt found = upgrades.rebuiltRepair();
            remember(upgrades);
            return found;
        }).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                say(upgrades.displayName() + "'s values could not be checked: " + causeOf(failure).getMessage());
                nextRepair(rebuilt, wrote);
                return;
            }
            found.problems().forEach(this::say);
            if (found.planned() == null || !askRepair(upgrades, found.planned())) {
                nextRepair(rebuilt, wrote);
                return;
            }
            String name = upgrades.displayName();
            CompletableFuture.supplyAsync(() -> {
                upgrades.snapshot("Before repairing values for the rebuilt " + name + " " + upgrades.currentVersion());
                return upgrades.applyRepair(found.planned());
            }).whenComplete((values, failed) -> Platform.runLater(() -> {
                if (failed != null) {
                    ThemedWindows.alert(Alert.AlertType.ERROR, "The repair did not run:\n\n"
                            + causeOf(failed).getMessage()).showAndWait();
                    nextRepair(rebuilt, wrote);
                    return;
                }
                // A holder the repair deleted: its buffer goes, and the editor moves off it, as an upgrade does.
                var active = state.getActiveFile();
                values.deleted().forEach(state::removeFile);
                if (active != null && values.deleted().contains(active.getPath())) {
                    state.setActiveFile(config.entrySourceFile());
                }
                say("Repaired " + values.files() + (values.files() == 1 ? " file" : " files") + " for " + name + ".");
                nextRepair(rebuilt, true);
            }));
        }));
    }

    /** Asks whether to write {@code planned}, what the rebuilt plugin needs of the bot's values. */
    private boolean askRepair(PluginUpgradeService upgrades, ManagedRepairs.Planned planned) {
        String name = upgrades.displayName();
        String text = name + " " + upgrades.currentVersion() + " was rebuilt, and this bot's values no longer fit it:"
                + "\n\n• " + String.join("\n• ", planned.sentences())
                + "\n\nRepair writes this, after saving a version of the bot. Cancel leaves the bot as written.";
        Alert ask = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, text);
        ask.initOwner(stage);
        ask.setHeaderText("Repair the bot's values for the rebuilt " + name + "?");
        ButtonType repair = new ButtonType("Repair", ButtonBar.ButtonData.OK_DONE);
        ask.getButtonTypes().setAll(repair, ButtonType.CANCEL);
        return ask.showAndWait().orElse(ButtonType.CANCEL) == repair;
    }

    /** Whether {@code upgrades}' pinned jar is the bytes it was when last checked for this project. Blocking. */
    private boolean checkedAlready(PluginUpgradeService upgrades) {
        return jarStamp(upgrades).equals(CHECKED_JARS.get(checkedKey(upgrades)));
    }

    private void remember(PluginUpgradeService upgrades) {
        CHECKED_JARS.put(checkedKey(upgrades), jarStamp(upgrades));
    }

    private String checkedKey(PluginUpgradeService upgrades) {
        return config.projectPath() + "|" + upgrades.coordinate();
    }

    /** The pinned jar's path, size and modification time; empty when it does not resolve. Blocking. */
    private static String jarStamp(PluginUpgradeService upgrades) {
        return upgrades.pinnedJar().map(jar -> {
            try {
                return jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
            } catch (IOException unreadable) {
                return "";
            }
        }).orElse("");
    }

    /** Adds {@code line} to the footer's status, after what the reload said. */
    private void say(String line) {
        status.setText(status.getText().isBlank() ? line : status.getText() + " " + line);
    }

    private static Throwable causeOf(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    private void afterInstalledChanged() {
        boolean wasDevMode = devMode.isSelected();
        devMode.setSelected(libraryService.devMode());   // the tab's Use dev mode turns it on too
        showLoadProblems();
        if (wasDevMode != devMode.isSelected()) browse.reload();
        browse.refreshInstalled();
        libraries.reload();
    }

    private void afterBrowseChanged() {
        showLoadProblems();
        installed.reload();
        libraries.reload();
    }

    private void afterLibrariesChanged() {
        showLoadProblems();
        installed.reload();
        browse.refreshInstalled();
    }

    /** Puts what the last bind could not load on screen, and takes the line away when there is nothing. */
    private void showLoadProblems() {
        String text = failureText(PluginHost.failures());
        List<String> unresolved = libraryService.unresolved();
        if (!unresolved.isEmpty()) {
            text = (text.isEmpty() ? "" : text + "\n") + "Could not download: " + String.join("; ", unresolved);
        }
        loadProblems.setText(text);
        loadProblems.setVisible(!text.isEmpty());
        loadProblems.setManaged(!text.isEmpty());
    }

    /** The shared {@link #localScan}, started off the FX thread the first time a tab asks. */
    private CompletableFuture<List<LocalBuilds.Build>> localBuilds() {
        if (localScan == null) localScan = CompletableFuture.supplyAsync(LocalBuilds::scan);
        return localScan;
    }

    /** What the Dev mode box's tooltip says it does, and what it does not change. */
    static final String DEV_MODE_TIP = "Load this project's plugin jars at a -SNAPSHOT version: the ones mvn install"
            + " put in this computer's ~/.m2. Try a plugin before its release, then press Reload after each"
            + " rebuild. This computer only; Publish still refuses a -SNAPSHOT pin.";

    /** What Reload adds about the dev builds it bound, or {@code ""} when there are none. */
    static String devText(List<String> devBuilds) {
        if (devBuilds.isEmpty()) return "";
        return " Dev builds: " + String.join(", ", devBuilds) + ".";
    }

    /** What Reload says it found: every plugin bound, by name. */
    static String loadedText(List<LoadedPlugin> plugins) {
        if (plugins.isEmpty()) return "No plugin loaded.";
        return plugins.size() + (plugins.size() == 1 ? " plugin" : " plugins") + " loaded: "
                + plugins.stream().map(LoadedPlugin::name).collect(Collectors.joining(", ")) + ".";
    }

    /**
     * The one line this window says about plugins that did not load, or {@code ""} when they all did.
     *
     * <p>Static and pure so it can be asserted without a scene. The failure list itself is
     * {@code PluginLoader.PluginFailure}, so the sentence is built from what the loader actually caught
     * rather than from a guess about what a user did.
     *
     * <p>It names the plugins rather than counting them: <i>2 plugins did not load</i> is a sentence nobody
     * can act on, and the provider class is the only identity available — a plugin that would not construct
     * never got to answer {@code id()}.
     */
    static String failureText(List<PluginLoader.PluginFailure> failures) {
        if (failures.isEmpty()) return "";
        String lead = failures.size() == 1
                ? "A plugin on this project's classpath did not load: "
                : failures.size() + " plugins on this project's classpath did not load: ";
        return lead + failures.stream().map(PluginLoader.PluginFailure::describe)
                .collect(Collectors.joining("; "))
                + ". Its features are absent from the editor; the project itself is unaffected.";
    }
}
