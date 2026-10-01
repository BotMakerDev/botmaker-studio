package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The <b>Browse</b> tab of <b>Project ▸ Plugins &amp; Libraries…</b> — the plugin registry, local builds, and
 * the Install button. It was the <b>Manage Plugins</b> window until 2026-09-29.
 *
 * <p>Installing is deliberately not special: it adds an ordinary dependency through {@link LibraryService},
 * so a plugin is on the project's classpath and {@code PluginHost.bind} finds it through the same
 * {@code ServiceLoader} pass that finds the SDK. A bespoke install path would be a privilege the first-party
 * plugin has and a third party's does not — which is the back door the whole platform exists to close.
 *
 * <p><b>An installed plugin offers no Remove here.</b> It did until 2026-09-29, as a bare pom edit beside the
 * Installed tab's checked removal, so the same plugin could leave a project two ways and only one of them
 * repaired the bot's calls into it. Its row sends the user to Installed instead.
 *
 * <p><b>The version installed is the registry's {@code verifiedVersion}, not the newest tag.</b> That is
 * the version the registry's gate actually loaded and checked; a newer tag may be one nothing has ever
 * run. Only an entry with no verified version falls back to asking JitPack for the newest.
 *
 * <p>Everything about the network here degrades to a sentence: an unreachable registry shows a message in
 * the empty list and nothing else changes, matching how {@code JitPackSearch} already treats a failure. A
 * catalog nobody can fetch must never stop somebody editing their bot.
 */
final class BrowsePluginsTab {

    private final LibraryService libraryService;
    private final PluginCatalog catalog;
    private final Runnable onShowInstalled;
    private final Runnable onChanged;

    private final ObservableList<PluginRegistry.Plugin> shown = FXCollections.observableArrayList();
    private final List<PluginRegistry.Plugin> all = new ArrayList<>();
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final ListView<PluginRegistry.Plugin> list = new ListView<>(shown);
    private final VBox root;

    /**
     * Everything the project's pom declares, re-read after every change so the badges stay honest.
     *
     * <p><b>Declared, not user-added</b>, and that distinction is the whole of the bug this fixed:
     * {@code LibraryService.currentLibraries()} answers the dependencies that are <em>not</em> built in, and
     * {@code botmaker-sdk} is built in — so the one plugin that exists read as not-installed forever, its
     * button said <i>Install</i> however many times it had been pressed, and each press appended another
     * {@code botmaker-sdk} to the pom.
     */
    private List<UserLibrary> installed = List.of();

    /**
     * @param onShowInstalled an installed row's <i>Manage…</i>: the window switches to Installed
     * @param onChanged       after an install wrote the pom, so the window's other tabs read it again
     */
    BrowsePluginsTab(LibraryService libraryService, PluginRegistry registry, JitPackSearch jitpack,
                     Runnable onShowInstalled, Runnable onChanged) {
        this.libraryService = libraryService;
        this.catalog = new PluginCatalog(registry, jitpack);
        this.onShowInstalled = onShowInstalled;
        this.onChanged = onChanged;

        installed = libraryService.declaredLibraries();

        searchField.setPromptText("Search plugins");
        searchField.textProperty().addListener((obs, old, text) -> refilter(text));

        list.setCellFactory(view -> new PluginCell());
        list.setPlaceholder(new Label("Loading…"));
        VBox.setVgrow(list, Priority.ALWAYS);

        // Said plainly, in the window, because the registry's README says it and a user installing from
        // here never reads that: the checks ask whether a plugin WORKS, never whether it is safe.
        Label caveat = new Label("Registry plugins are curated and checked for loading, not reviewed for"
                + " safety — a plugin runs with Studio's own permissions.");
        caveat.setWrapText(true);
        caveat.getStyleClass().add("sdk-upgrade-empty");

        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        HBox bar = new HBox(10, progress, statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(12, searchField, list, caveat, bar);
        root.setPadding(new Insets(12, 0, 0, 0));
        load();
    }

    /** The tab's content. */
    Node node() {
        return root;
    }

    /** Re-reads what the pom declares and redraws the badges — another tab or a reload changed it. */
    void refreshInstalled() {
        installed = libraryService.declaredLibraries();
        list.refresh();
    }

    private void load() {
        catalog.rows()
                .thenAccept(rows -> Platform.runLater(() -> {
                    all.clear();
                    all.addAll(rows);
                    refilter(searchField.getText());
                    list.setPlaceholder(new Label(rows.isEmpty()
                            ? "No plugins listed — the registry is empty or could not be reached."
                            : "No plugin matches that search."));
                }));
    }

    /** The ids of the plugins bound to the open project — whatever put them on its classpath. */
    private static List<String> boundPluginIds() {
        return PluginHost.plugins().stream().map(StudioPlugin::id).toList();
    }

    /**
     * Why this plugin must not be declared here, or {@code ""} when it may be.
     *
     * <p>A plugin already <b>bound</b> but not <b>declared</b> is one another plugin depends on — the SDK
     * brings plugin-basics, and a bot's pom naming basics beside it is the case the umbrella's rules call out
     * by name. Maven's nearest-wins would then let this pom pin a version the plugin that depends on it was
     * never built against, and the failure that produces is a linkage error inside somebody else's plugin.
     *
     * <p>Static and pure so it is asserted with no scene.
     */
    static String alreadyProvided(PluginRegistry.Plugin plugin, List<UserLibrary> declared,
                                  List<String> boundIds) {
        // A coordinate the pom already declares is an ordinary re-install or version change, whatever else
        // is bound: this tab is idempotent by coordinate and must stay so.
        if (plugin.isInstalledIn(declared)) return "";
        if (plugin.id().isBlank() || !boundIds.contains(plugin.id())) return "";
        String name = plugin.name().isBlank() ? plugin.id() : plugin.name();
        return name + " is already on this project's classpath — another plugin it is a dependency of brings "
                + "it. Declaring it here too would let this pom pin a version that plugin was never built "
                + "against. Change the version of the plugin that brings it instead.";
    }

    // merge and version moved to sharing/PluginCatalog on 2026-10-01, so New Project offers the same rows at the
    // same versions as this tab.

    private void refilter(String query) {
        shown.setAll(all.stream().filter(plugin -> plugin.matches(query)).toList());
    }

    // -------------------------------------------------------------------------
    // Install
    // -------------------------------------------------------------------------

    /**
     * Declares the plugin's coordinate in the project's pom.
     *
     * <p>Idempotent by coordinate: re-installing replaces the entry rather than adding a second one, since
     * two versions of one artifact on a classpath is the state that produces the least diagnosable failure
     * this platform has. That is enforced by {@code MavenService.installPlugin} editing the pom in place.
     *
     * <p>It is also where a plugin that needs something of the host gets it: the entry's
     * {@code editorDependencies} are declared {@code provided} beside the plugin itself. The list is the
     * registry's, so any plugin can have one.
     */
    private void install(PluginRegistry.Plugin plugin) {
        if (!plugin.isInstallable()) {
            error("This entry has no resolvable coordinate — nothing to install.");
            return;
        }
        String provided = alreadyProvided(plugin, installed, boundPluginIds());
        if (!provided.isEmpty()) {
            error(provided);
            return;
        }
        busy(true);
        catalog.version(plugin).thenAccept(version -> {
            if (version == null || version.isBlank()) {
                Platform.runLater(() -> {
                    busy(false);
                    error("Could not resolve a version for " + plugin.coordinate() + ".");
                });
                return;
            }
            apply(libraryService.installPlugin(
                            new UserLibrary(plugin.groupId(), plugin.artifactId(), version),
                            plugin.editorLibraries()),
                    plugin.name() + " " + version + " installed.");
        }).exceptionally(failure -> {
            // A version lookup that fails (JitPack unreachable) used to leave the list disabled for good.
            Platform.runLater(() -> {
                busy(false);
                error("Could not resolve a version for " + plugin.coordinate() + ": " + rootMessage(failure));
            });
            return null;
        });
    }

    /** Reports the outcome of a pom write, then re-reads what the pom now declares. */
    private void apply(CompletableFuture<List<MavenService.Shadowed>> write, String done) {
        write.whenComplete((dropped, err) -> Platform.runLater(() -> {
            busy(false);
            refreshInstalled();
            onChanged.run();
            if (err != null) {
                error(rootMessage(err));
            } else {
                statusLabel.setStyle("-fx-text-fill: gray;");
                String removed = MavenService.Shadowed.sentence(dropped);
                statusLabel.setText(removed.isEmpty() ? done : done + " " + removed);
            }
        }));
    }

    private void busy(boolean busy) {
        progress.setVisible(busy);
        list.setDisable(busy);
        if (busy) {
            statusLabel.setText("");
        }
    }

    private void error(String message) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText(message);
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    // -------------------------------------------------------------------------
    // One row
    // -------------------------------------------------------------------------

    private final class PluginCell extends ListCell<PluginRegistry.Plugin> {

        @Override
        protected void updateItem(PluginRegistry.Plugin plugin, boolean empty) {
            super.updateItem(plugin, empty);
            if (empty || plugin == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(plugin.name().isBlank() ? plugin.id() : plugin.name());
            name.setStyle("-fx-font-weight: bold;");
            Label coordinate = new Label(plugin.coordinate());
            coordinate.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            Label description = new Label(plugin.description());
            description.setWrapText(true);

            VBox text = new VBox(2, name, coordinate, description);
            if (!plugin.repo().isBlank()) {
                Hyperlink source = new Hyperlink(plugin.repo());
                source.setStyle("-fx-font-size: 11px;");
                source.setOnAction(e -> BrowserLauncher.open(plugin.htmlUrl()));
                text.getChildren().add(source);
            }
            HBox.setHgrow(text, Priority.ALWAYS);

            Node action;
            // Brought by another plugin: nothing to install, and nothing this pom can remove. Saying
            // "Install" and then refusing the click is what this label replaces.
            String provided = alreadyProvided(plugin, installed, boundPluginIds());
            if (!provided.isEmpty()) {
                Label state = new Label("Included");
                state.setTooltip(new Tooltip(provided));
                state.getStyleClass().add("plugin-provided-label");
                action = state;
            } else if (plugin.isInstalledIn(installed)) {
                Button manage = new Button("Installed — manage…");
                manage.setTooltip(new Tooltip("Change its version or remove it in the Installed tab, where "
                        + "the bot's calls into it are checked and repaired."));
                manage.setOnAction(e -> onShowInstalled.run());
                action = manage;
            } else {
                Button install = new Button("Install");
                install.setOnAction(e -> install(plugin));
                action = install;
            }

            HBox row = new HBox(10, text, action);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 2, 6, 2));
            setGraphic(row);
        }
    }
}
