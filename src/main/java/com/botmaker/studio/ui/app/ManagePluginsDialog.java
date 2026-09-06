package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * <b>Project ▸ Manage Plugins…</b> — the browser over the plugin registry, and the install button.
 *
 * <p>Installing is deliberately not special: it adds an ordinary dependency through {@link LibraryService},
 * exactly as <b>Manage Libraries</b> does, so a plugin is on the project's classpath and
 * {@code PluginHost.bind} finds it through the same {@code ServiceLoader} pass that finds the SDK. A
 * bespoke install path would be a privilege the first-party plugin has and a third party's does not —
 * which is the back door the whole platform exists to close. The corollary is that <b>this dialog can be
 * undone from Manage Libraries</b>: a plugin is a row there like any other, which is where its version is
 * changed.
 *
 * <p><b>The version installed is the registry's {@code verifiedVersion}, not the newest tag.</b> That is
 * the version the registry's gate actually loaded and checked; a newer tag may be one nothing has ever
 * run. Only an entry with no verified version falls back to asking JitPack for the newest.
 *
 * <p>Everything about the network here degrades to a sentence: an unreachable registry shows a message in
 * the empty list and nothing else changes, matching how {@code JitPackSearch} already treats a failure. A
 * catalog nobody can fetch must never stop somebody editing their bot.
 */
public final class ManagePluginsDialog {

    private final Window owner;
    private final LibraryService libraryService;
    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    private final ObservableList<PluginRegistry.Plugin> shown = FXCollections.observableArrayList();
    private final List<PluginRegistry.Plugin> all = new ArrayList<>();
    private final TextField searchField = new TextField();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final ListView<PluginRegistry.Plugin> list = new ListView<>(shown);

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

    /** The coordinates a dev build in {@code ~/.m2} answers for, so a row can say where its version came from. */
    private final Set<String> localCoordinates = new HashSet<>();

    private Stage stage;

    public ManagePluginsDialog(Window owner, LibraryService libraryService, PluginRegistry registry,
                               JitPackSearch jitpack) {
        this.owner = owner;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Manage Plugins");

        installed = libraryService.declaredLibraries();

        searchField.setPromptText("Search plugins");
        searchField.textProperty().addListener((obs, old, text) -> refilter(text));

        list.setCellFactory(view -> new PluginCell());
        list.setPlaceholder(new Label("Loading…"));
        VBox.setVgrow(list, Priority.ALWAYS);

        Label hint = new Label("A plugin is an ordinary dependency: it is added to this project's pom, and"
                + " its version can be changed in Manage Libraries.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // Said plainly, in the dialog, because the registry's README says it and a user installing from
        // here never reads that: the checks ask whether a plugin WORKS, never whether it is safe.
        Label caveat = new Label("Registry plugins are curated and checked for loading, not reviewed for"
                + " safety — a plugin runs with Studio's own permissions.");
        caveat.setWrapText(true);
        caveat.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // What the last bind could not load. Empty is the ordinary state and the row then takes no height:
        // this is the one place a user can be told the difference between "this project pins no plugin" and
        // "this project pins a plugin that is broken", which are the same empty palette and completely
        // different problems. Three incidents in this project's record end with the words "an empty palette
        // and one line on stderr"; this is where that line goes now.
        Label failed = new Label(failureText());
        failed.setWrapText(true);
        failed.setStyle("-fx-font-size: 11px; -fx-text-fill: #b00020;");
        failed.setVisible(!failed.getText().isEmpty());
        failed.setManaged(failed.isVisible());

        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox bar = new HBox(10, progress, statusLabel, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, searchField, list, failed, hint, caveat, bar);
        root.setPadding(new Insets(16));

        stage.setScene(ThemedWindows.scene(root, 620, 520));
        stage.show();

        load();
    }

    private void load() {
        // The ~/.m2 scan opens jars, so it is not the FX thread's work; it is also the half that must not
        // wait on the network, since a plugin author testing an unpublished build may have no registry at
        // all. Both halves are joined before anything is shown so the rows never re-order under the mouse.
        CompletableFuture<List<MavenService.LocalPluginBuild>> local =
                CompletableFuture.supplyAsync(MavenService::localPluginBuilds);
        registry.browse().thenCombine(local, (plugins, builds) -> merge(plugins, builds))
                .thenAccept(rows -> Platform.runLater(() -> {
                    all.clear();
                    all.addAll(rows);
                    refilter(searchField.getText());
                    list.setPlaceholder(new Label(rows.isEmpty()
                            ? "No plugins listed — the registry is empty or could not be reached."
                            : "No plugin matches that search."));
                }));
    }

    /**
     * The registry's entries, with what is built locally taking precedence over what is published.
     *
     * <p>A local build of a coordinate the registry also lists <b>replaces that entry's version</b> rather
     * than adding a second row: two rows for one artifact would offer to install two versions of it, and a
     * developer who has just built one wants the one they built. A local build nobody has published yet
     * becomes a row of its own, at the top, because it is the row they came here for.
     *
     * <p>Only ever populated in a dev build — {@link MavenService#localPluginBuilds()} answers empty
     * otherwise — so a released Studio shows exactly the registry and nothing else.
     */
    private static String failureText() {
        return failureText(PluginHost.failures());
    }

    /**
     * The one line this dialog says about plugins that did not load, or {@code ""} when they all did.
     *
     * <p>Static and pure so it can be asserted without a scene — the split this repo already uses for
     * {@code BlockTree} and {@code PluginRegistry.Plugin}. The failure list itself is
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
                .collect(java.util.stream.Collectors.joining("; "))
                + ". Its features are absent from the editor; the project itself is unaffected.";
    }

    private List<PluginRegistry.Plugin> merge(List<PluginRegistry.Plugin> published,
                                              List<MavenService.LocalPluginBuild> builds) {
        localCoordinates.clear();
        List<PluginRegistry.Plugin> rows = new ArrayList<>(published);
        for (MavenService.LocalPluginBuild build : builds) {
            localCoordinates.add(build.coordinate());
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).coordinate().equals(build.coordinate())) at = i;
            }
            if (at >= 0) {
                PluginRegistry.Plugin entry = rows.get(at);
                // The version is the local build's; everything else is still the registry's, the editor
                // dependencies included — a developer's own build of a plugin needs exactly what the
                // published one does.
                rows.set(at, new PluginRegistry.Plugin(entry.id(), entry.name(), entry.coordinate(),
                        entry.repo(), entry.description(), entry.tags(), entry.minContractVersion(),
                        entry.valueTypeIds(), entry.editorDependencies(), build.version(),
                        entry.verifiedAt()));
            } else {
                // A local build the registry has never seen has no entry to read a list from, so installing
                // it declares the plugin alone. That is the honest answer — nothing here can know what a
                // jar's optional dependencies are — and the way out is `botmaker plugin publish`.
                rows.add(0, new PluginRegistry.Plugin(build.coordinate(), build.artifactId(),
                        build.coordinate(), "", "Built locally into ~/.m2 — not published.", List.of(), "",
                        List.of(), List.of(), build.version(), ""));
            }
        }
        return rows;
    }

    private void refilter(String query) {
        shown.setAll(all.stream().filter(plugin -> plugin.matches(query)).toList());
    }

    // -------------------------------------------------------------------------
    // Install / remove
    // -------------------------------------------------------------------------

    /**
     * Declares the plugin's coordinate in the project's pom.
     *
     * <p>Idempotent by coordinate: re-installing replaces the entry rather than adding a second one, since
     * two versions of one artifact on a classpath is the state that produces the least diagnosable failure
     * this platform has. That is enforced by {@code MavenService.installPlugin} editing the pom in place —
     * it used to be attempted here, by rebuilding the user-library list, which could not see a plugin the
     * pom classes as built in and so wrote the SDK twice.
     *
     * <p>It is also where a plugin that needs something of the host gets it: the entry's
     * {@code editorDependencies} are declared {@code provided} beside the plugin itself. Until 2026-09-06
     * that was an {@code if (isSdk(…))} inside {@code MavenService} over a list written in Studio's source —
     * the privilege this class's own javadoc says a plugin platform must not grant. The list is the
     * registry's now, so a second plugin can have one.
     */
    private void install(PluginRegistry.Plugin plugin) {
        if (!plugin.isInstallable()) {
            error("This entry has no resolvable coordinate — nothing to install.");
            return;
        }
        busy(true);
        version(plugin).thenAccept(version -> {
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
        });
    }

    private void remove(PluginRegistry.Plugin plugin) {
        busy(true);
        apply(libraryService.removePlugin(plugin.groupId(), plugin.artifactId(),
                        plugin.editorLibraries()),
                plugin.name() + " removed.");
    }

    /** Reports the outcome of a pom write, then re-reads what the pom now declares. */
    private void apply(CompletableFuture<Void> write, String done) {
        write.whenComplete((ok, err) -> Platform.runLater(() -> {
            busy(false);
            installed = libraryService.declaredLibraries();
            // The badges are per-row, so the rows are what have to be redrawn.
            list.refresh();
            if (err != null) {
                error(rootMessage(err));
            } else {
                statusLabel.setStyle("-fx-text-fill: gray;");
                statusLabel.setText(done);
            }
        }));
    }

    /** The verified version, or — only when the entry carries none — JitPack's newest. */
    private CompletableFuture<String> version(PluginRegistry.Plugin plugin) {
        if (!plugin.verifiedVersion().isBlank()) {
            return CompletableFuture.completedFuture(plugin.verifiedVersion());
        }
        return jitpack.fetchLatestVersion(plugin.groupId(), plugin.artifactId());
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
            // The same wording the SDK dropdown uses for the same thing, because it is the same thing: a
            // build in ~/.m2 that no repository has ever served.
            boolean localBuild = localCoordinates.contains(plugin.coordinate());
            Label coordinate = new Label(plugin.coordinate()
                    + (localBuild ? "  " + plugin.verifiedVersion() + " (local build)" : ""));
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

            boolean here = plugin.isInstalledIn(installed);
            Button action = new Button(here ? "Remove" : "Install");
            action.setOnAction(e -> {
                if (here) {
                    remove(plugin);
                } else {
                    install(plugin);
                }
            });

            HBox row = new HBox(10, text, action);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 2, 6, 2));
            setGraphic(row);
        }
    }
}
