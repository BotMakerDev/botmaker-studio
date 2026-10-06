package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.LocalBuilds;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import com.botmaker.studio.sharing.PluginRegistry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    private final TabPane tabs = new TabPane();
    private final Label loadProblems = new Label();
    private final Label status = new Label();
    private final CheckBox devMode = new CheckBox("Dev mode");

    /**
     * The {@code ~/.m2} scan both tabs read in dev mode, run once and shared until the next reload: it walks
     * the whole local repository and opens jars. Touched on the FX thread only.
     */
    private CompletableFuture<List<LocalBuilds.Build>> localScan;
    private InstalledPluginsTab installed;
    private BrowsePluginsTab browse;
    private LibrariesTab libraries;
    private Stage stage;

    /** @param onOpenReview raises the shell's Review tab; offered after a pass rewrote the bot's code */
    public PluginsWindow(Window owner, ProjectConfig config, ProjectState state, LibraryService libraryService,
                         PluginRegistry registry, JitPackSearch jitpack, MavenCentralSearch mavenCentral,
                         Runnable onOpenReview) {
        this.owner = owner;
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
        this.mavenCentral = mavenCentral;
        this.onOpenReview = onOpenReview;
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
                this::afterInstalledChanged);
        browse = new BrowsePluginsTab(libraryService, registry, jitpack, this::localBuilds,
                () -> select(Section.INSTALLED), this::afterBrowseChanged);
        libraries = new LibrariesTab(libraryService, mavenCentral, jitpack,
                InstalledPlugin.jarDeclaresPlugin(config.projectPath()), this::afterLibrariesChanged);

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
            showLoadProblems();
            localScan = null;       // a rebuild or a dev-mode switch changes which ~/.m2 builds are offered
            installed.reload();
            browse.reload();
            browse.refreshInstalled();
        }));
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
    static String loadedText(List<StudioPlugin> plugins) {
        if (plugins.isEmpty()) return "No plugin loaded.";
        return plugins.size() + (plugins.size() == 1 ? " plugin" : " plugins") + " loaded: "
                + plugins.stream().map(StudioPlugin::displayName).collect(Collectors.joining(", ")) + ".";
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
