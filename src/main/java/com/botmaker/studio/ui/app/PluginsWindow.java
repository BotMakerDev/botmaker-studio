package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import com.botmaker.studio.sharing.PluginRegistry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
 *   <li><b>Browse</b> ({@link BrowsePluginsTab}) — what it could run: the registry and local builds;</li>
 *   <li><b>Libraries</b> ({@link LibrariesTab}) — its ordinary Maven dependencies, plugins held back.</li>
 * </ul>
 * Reload sits in the header because it is about all three, and so does the line naming what did not load,
 * which is the first thing a user sent here by the canvas banner needs to read. Manage Imports is deleted:
 * every edit imports what it writes, and an unused import compiles.
 *
 * <p>A pom edited outside Studio rebinds by itself ({@code LibraryService.watchPom}), so Reload is the plugin
 * author's button: the jar's bytes changed in {@code ~/.m2} and the pom did not.
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

        installed = new InstalledPluginsTab(config, state, libraryService, registry, jitpack,
                () -> select(Section.BROWSE),
                () -> {
                    stage.close();
                    onOpenReview.run();
                },
                this::afterInstalledChanged);
        browse = new BrowsePluginsTab(libraryService, registry, jitpack,
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
        reload.setTooltip(new Tooltip("Load this project's plugin jars again, without changing pom.xml — for a"
                + " plugin you just rebuilt into ~/.m2."));
        reload.setOnAction(e -> reload(reload));

        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        status.getStyleClass().add("sdk-upgrade-detail");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, reload, status, spacer, close);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, loadProblems, tabs, footer);
        root.setPadding(new Insets(12, 16, 16, 16));
        window.show(root);
    }

    private void select(Section section) {
        tabs.getSelectionModel().select(section.ordinal());
    }

    private void reload(Button button) {
        button.setDisable(true);
        status.setText("Reloading plugins…");
        libraryService.reloadPlugins().whenComplete((ignored, failure) -> Platform.runLater(() -> {
            button.setDisable(false);
            if (failure != null) {
                status.setText("Could not reload plugins: " + failure.getMessage());
                return;
            }
            // Named, not counted: a reload that found the same plugins looks exactly like one that did
            // nothing, and an author who forgot to run mvn install needs to tell those apart.
            status.setText(loadedText(PluginHost.plugins()));
            showLoadProblems();
            installed.reload();
            browse.refreshInstalled();
        }));
    }

    private void afterInstalledChanged() {
        showLoadProblems();
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
