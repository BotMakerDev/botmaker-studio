package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The <b>Libraries</b> tab of <b>Project ▸ Plugins &amp; Libraries…</b> — the project's ordinary Maven
 * dependencies. It was the <b>Manage Libraries</b> window until 2026-09-29. Coordinates are entered in a
 * single {@code group:artifact[:version]} field with IntelliJ-style suggestions fetched live from Maven
 * Central ({@link MavenCentralSearch}); applying delegates to {@link LibraryService}, which rewrites the pom,
 * re-resolves the classpath and refreshes the type index off the FX thread.
 *
 * <p><b>Plugins are not rows here.</b> The SDK was a pinned row with an editable version, and any other
 * plugin an ordinary one — so a plugin's version could move with a cell edit, past the report and the repair
 * the Installed tab runs for exactly that change. A declared library whose jar is a plugin is held back,
 * written again unchanged on Apply, and changed only in Installed.
 */
final class LibrariesTab {

    private static final int SUGGESTION_LIMIT = 15;
    private static final int VERSION_LIMIT = 40;

    /** Sentinel version that resolves to the newest concrete version at apply time (see {@link #resolveLatest}). */
    private static final String LATEST = "latest";

    private final LibraryService libraryService;
    private final MavenCentralSearch search;
    private final JitPackSearch jitpack;
    private final Predicate<UserLibrary> isPlugin;
    private final PluginRegistry registry;
    private final Runnable onChanged;

    /** <i>Plugins are on the Installed tab (N)</i>: the door to where a plugin's version changes. */
    private final Hyperlink pluginsLink = new Hyperlink();

    /** {@code groupId:artifactId} of each row only a plugin this project lacks needs; marked <i>not needed</i>. */
    private java.util.Set<String> unneeded = java.util.Set.of();

    /** Bumped by every {@link #markUnneeded}, so an older registry reply never marks a newer table. */
    private int unneededAsked;

    /** The rows shown: every user library that is not a plugin. */
    private final ObservableList<UserLibrary> libraries = FXCollections.observableArrayList();
    /** The user libraries that are plugins: not shown, written back as they are. */
    private List<UserLibrary> heldPlugins = List.of();
    private final TextField coordinateField = new TextField();
    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final ContextMenu suggestions = new ContextMenu();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button apply = new Button("Apply");
    private final Button revert = new Button("Revert");
    private final VBox root = new VBox(12);

    /**
     * @param isPlugin whether a declared library's jar is a plugin ({@code InstalledPlugin.jarDeclaresPlugin});
     *                 asked off the FX thread, since it may resolve the jar
     * @param registry       the plugin registry: whose editor dependencies a row is
     * @param onShowInstalled the plugins line's link: the window switches to Installed
     * @param onChanged after Apply wrote the pom, so the window's other tabs read it again
     */
    LibrariesTab(LibraryService libraryService, MavenCentralSearch search, JitPackSearch jitpack,
                 Predicate<UserLibrary> isPlugin, PluginRegistry registry, Runnable onShowInstalled,
                 Runnable onChanged) {
        this.libraryService = libraryService;
        this.search = search;
        this.jitpack = jitpack;
        this.isPlugin = isPlugin;
        this.registry = registry;
        this.onChanged = onChanged;
        pluginsLink.setOnAction(e -> onShowInstalled.run());

        root.setPadding(new Insets(12, 0, 0, 0));
        root.getChildren().addAll(buildTable(), buildAddRow(), buildButtonBar());
        reload();
    }

    /** The tab's content. */
    Node node() {
        return root;
    }

    /** Reads the pom again, dropping any edit not applied — the Revert button, and after another tab wrote. */
    void reload() {
        statusLabel.setText("");
        setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            List<UserLibrary> declared = libraryService.currentLibraries();
            return declared.stream().collect(Collectors.partitioningBy(isPlugin));
        }).whenComplete((split, err) -> Platform.runLater(() -> {
            setBusy(false);
            if (err != null) {
                error("Could not read this project's libraries: " + rootMessage(err));
                return;
            }
            heldPlugins = List.copyOf(split.get(true));
            libraries.setAll(split.get(false));
            pluginsLink.setText("Plugins are on the Installed tab (" + heldPlugins.size() + ")");
            markUnneeded(split.get(false), split.get(true));
        }));
    }

    /** Fills {@link #unneeded} once the registry answers; a registry that cannot be read marks nothing. */
    private void markUnneeded(List<UserLibrary> rows, List<UserLibrary> plugins) {
        List<com.botmaker.studio.plugin.PluginHost.LoadedPlugin> loaded =
                com.botmaker.studio.plugin.PluginHost.loaded();
        int asked = ++unneededAsked;
        registry.browse().thenAccept(entries -> {
            java.util.Set<String> found = PluginCatalog.unneededEditorDependencies(rows, entries,
                    entry -> entry.isInstalledIn(plugins)
                            || loaded.stream().anyMatch(l -> l.isOf(entry.groupId(), entry.artifactId())));
            Platform.runLater(() -> {
                if (asked != unneededAsked) return;
                unneeded = found;
                table.refresh();
            });
        });
    }

    // -------------------------------------------------------------------------
    // Current libraries table
    // -------------------------------------------------------------------------

    private final TableView<UserLibrary> table = new TableView<>(libraries);

    private VBox buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setEditable(true);
        table.setPlaceholder(new Label("No additional libraries. Built-in dependencies are managed automatically."));

        TableColumn<UserLibrary, String> groupCol = new TableColumn<>("Group");
        groupCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().groupId()));
        TableColumn<UserLibrary, String> artifactCol = new TableColumn<>("Artifact");
        artifactCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().artifactId()));
        TableColumn<UserLibrary, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().version()));
        versionCol.setCellFactory(col -> new VersionCell());
        TableColumn<UserLibrary, String> noteCol = new TableColumn<>("");
        noteCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                unneeded.contains(c.getValue().groupArtifact()) ? "not needed" : ""));
        noteCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                boolean show = !empty && item != null && !item.isEmpty();
                setText(show ? item : null);
                setTooltip(show ? new javafx.scene.control.Tooltip("Only a plugin this project does not have "
                        + "lists it as an editor dependency. Remove it, then Apply.") : null);
                getStyleClass().remove("text-muted");
                if (show) getStyleClass().add("text-muted");
            }
        });
        table.getColumns().addAll(List.of(groupCol, artifactCol, versionCol, noteCol));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> removeBtn.setDisable(sel == null));
        removeBtn.setOnAction(e -> {
            UserLibrary sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) libraries.remove(sel);
        });

        HBox tableButtons = new HBox(removeBtn);
        tableButtons.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("Project libraries");
        heading.setStyle("-fx-font-weight: bold;");
        Label hint = new Label("Double-click a version to change it. A plugin's version changes in Installed,"
                + " where the bot's calls into it are checked.");
        hint.setWrapText(true);
        hint.getStyleClass().add("sdk-upgrade-empty");
        VBox box = new VBox(6, heading, table, hint, pluginsLink, tableButtons);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    /**
     * Editable version cell backed by a {@link ComboBox} that lazily loads available versions on edit —
     * from JitPack for {@code com.github.*} coordinates, otherwise Maven Central. Committing
     * replaces the immutable {@link UserLibrary} at this row with the chosen version.
     */
    private final class VersionCell extends javafx.scene.control.TableCell<UserLibrary, String> {
        private final ComboBox<String> combo = new ComboBox<>();
        /**
         * True while the combo's items/value are being mutated programmatically (initial seed + async
         * version load). Setting a {@link ComboBox}'s value fires its {@code onAction}; without this guard
         * the async {@code setValue} would call {@link #commitEdit} and tear down the editor the instant
         * versions finished loading — making the version look impossible to change.
         */
        private boolean loading;

        VersionCell() {
            combo.setEditable(true);
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> {
                if (!loading && isEditing() && combo.getValue() != null) commitEdit(combo.getValue().trim());
            });
            combo.getEditor().setOnAction(e -> {
                if (!loading && isEditing()) commitEdit(combo.getEditor().getText().trim());
            });
            // Also commit a typed-but-not-Enter'd version when focus leaves the cell (e.g. the user
            // types a version and clicks Apply). Without this the edit is silently cancelled and the
            // old version is what gets written to the pom. Guarded on the popup being closed so opening
            // the dropdown to pick a value doesn't prematurely commit.
            combo.getEditor().focusedProperty().addListener((obs, was, focused) -> {
                if (!loading && !focused && isEditing() && !combo.isShowing()) {
                    String text = combo.getEditor().getText();
                    if (text != null && !text.isBlank()) commitEdit(text.trim());
                }
            });
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            loading = true;
            combo.getItems().setAll(getItem() == null ? List.of() : List.of(getItem()));
            combo.setValue(getItem());
            loading = false;
            setText(null);
            setGraphic(combo);
            combo.requestFocus();
            UserLibrary lib = getCurrentRow();
            if (lib != null) loadVersions(lib);
        }

        /** Populates the combo with available versions for {@code lib} (newest first, with a "latest"
         * option on top), preserving the current selection and suppressing spurious commits. */
        private void loadVersions(UserLibrary lib) {
            String current = combo.getValue();
            CompletableFuture<List<String>> future = lib.groupId().startsWith("com.github.")
                    ? jitpack.fetchVersions(lib.groupId(), lib.artifactId())
                    : search.fetchVersions(lib.groupId(), lib.artifactId(), VERSION_LIMIT);
            future.thenAccept(versions -> Platform.runLater(() -> {
                loading = true;
                combo.getItems().setAll(withLatest(versions));
                if (current != null && !current.isBlank()) combo.setValue(current);
                loading = false;
            }));
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem());
            setGraphic(null);
        }

        @Override
        public void commitEdit(String newVersion) {
            super.commitEdit(newVersion);
            int idx = getIndex();
            if (newVersion != null && !newVersion.isBlank() && idx >= 0 && idx < libraries.size()) {
                UserLibrary lib = libraries.get(idx);
                if (!newVersion.equals(lib.version())) {
                    libraries.set(idx, new UserLibrary(lib.groupId(), lib.artifactId(), newVersion));
                }
            }
            setText(getItem());
            setGraphic(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                combo.setValue(item);
                setText(null);
                setGraphic(combo);
            } else {
                setText(item);
                setGraphic(null);
            }
        }

        private UserLibrary getCurrentRow() {
            int idx = getIndex();
            return (idx >= 0 && idx < libraries.size()) ? libraries.get(idx) : null;
        }
    }

    /** Prepends the {@link #LATEST} sentinel to a fetched (newest-first) version list. */
    private static List<String> withLatest(List<String> versions) {
        List<String> items = new java.util.ArrayList<>(versions.size() + 1);
        items.add(LATEST);
        items.addAll(versions);
        return items;
    }

    /**
     * Resolves a library's version, mapping the {@link #LATEST} sentinel to the newest concrete version
     * (JitPack for {@code com.github.*}, otherwise Maven Central — both list newest first). Non-sentinel
     * versions pass through unchanged. Resolves to {@code ""} if no version could be determined.
     */
    private CompletableFuture<String> resolveLatest(UserLibrary lib) {
        String version = lib.version() == null ? "" : lib.version().trim();
        if (!LATEST.equalsIgnoreCase(version)) {
            return CompletableFuture.completedFuture(version);
        }
        return lib.groupId().startsWith("com.github.")
                ? jitpack.fetchLatestVersion(lib.groupId(), lib.artifactId())
                : search.fetchVersions(lib.groupId(), lib.artifactId(), 1)
                        .thenApply(v -> v.isEmpty() ? "" : v.get(0));
    }

    // -------------------------------------------------------------------------
    // Add row with Maven Central autocomplete
    // -------------------------------------------------------------------------

    private HBox buildAddRow() {
        coordinateField.setPromptText("group:artifact (start typing to search Maven Central)");
        HBox.setHgrow(coordinateField, Priority.ALWAYS);
        versionCombo.setEditable(true);
        versionCombo.setPromptText("version");
        versionCombo.setPrefWidth(160);

        debounce.setOnFinished(e -> runSearch(coordinateField.getText()));
        coordinateField.textProperty().addListener((obs, old, text) -> debounce.playFromStart());

        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> addCurrent());

        HBox row = new HBox(8, coordinateField, versionCombo, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void runSearch(String query) {
        if (query == null || query.isBlank() || query.contains(":") && query.split(":").length >= 3) {
            suggestions.hide();
            return;
        }
        search.searchArtifacts(query.trim(), SUGGESTION_LIMIT)
                .thenAccept(results -> Platform.runLater(() -> showSuggestions(results)));
    }

    private void showSuggestions(List<UserLibrary> results) {
        suggestions.getItems().clear();
        if (results.isEmpty()) {
            suggestions.hide();
            return;
        }
        for (UserLibrary lib : results) {
            MenuItem item = new MenuItem(lib.groupArtifact());
            item.setOnAction(e -> selectSuggestion(lib));
            suggestions.getItems().add(item);
        }
        if (!suggestions.isShowing()) {
            suggestions.show(coordinateField, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    private void selectSuggestion(UserLibrary lib) {
        suggestions.hide();
        coordinateField.setText(lib.groupArtifact());
        coordinateField.positionCaret(coordinateField.getLength());
        loadVersions(lib.groupId(), lib.artifactId(), lib.version());
    }

    private void loadVersions(String groupId, String artifactId, String preferred) {
        versionCombo.getItems().clear();
        search.fetchVersions(groupId, artifactId, VERSION_LIMIT)
                .thenAccept(versions -> Platform.runLater(() -> {
                    versionCombo.getItems().setAll(withLatest(versions));
                    if (!preferred.isBlank()) {
                        versionCombo.setValue(preferred);
                    } else if (!versions.isEmpty()) {
                        versionCombo.setValue(versions.get(0));
                    }
                }));
    }

    private void addCurrent() {
        statusLabel.setText("");
        UserLibrary lib;
        try {
            String coord = coordinateField.getText() == null ? "" : coordinateField.getText().trim();
            String version = versionCombo.getValue() == null ? "" : versionCombo.getValue().trim();
            if (coord.split(":").length >= 3) {
                lib = UserLibrary.parse(coord);
            } else {
                lib = UserLibrary.parse(coord + ":" + version);
            }
        } catch (IllegalArgumentException ex) {
            error("Enter a group:artifact and a version.");
            return;
        }

        boolean exists = libraries.stream().anyMatch(l -> l.groupArtifact().equals(lib.groupArtifact()));
        if (exists) {
            error(lib.groupArtifact() + " is already in the list.");
            return;
        }
        if (heldPlugins.stream().anyMatch(l -> l.groupArtifact().equals(lib.groupArtifact()))) {
            error(lib.groupArtifact() + " is a plugin this project installs; change it in Installed.");
            return;
        }
        libraries.add(lib);
        coordinateField.clear();
        versionCombo.getItems().clear();
        versionCombo.setValue(null);
    }

    // -------------------------------------------------------------------------
    // Apply / Revert
    // -------------------------------------------------------------------------

    private HBox buildButtonBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setWrapText(true);

        // Revert, not Cancel: the window stays open over its other tabs, so dropping the edits is the verb.
        revert.setOnAction(e -> reload());
        apply.setOnAction(e -> apply());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, statusLabel, spacer, revert, apply);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void apply() {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText("");
        setBusy(true);

        // Resolve any "latest" versions to their concrete newest before writing the pom, so the pom stays
        // pinned to a real version. Each row resolves independently and off the FX thread.
        List<CompletableFuture<UserLibrary>> resolved = libraries.stream()
                .map(lib -> resolveLatest(lib)
                        .thenApply(v -> new UserLibrary(lib.groupId(), lib.artifactId(), v)))
                .collect(Collectors.toList());

        CompletableFuture.allOf(resolved.toArray(new CompletableFuture[0]))
                .thenAccept(ignored -> {
                    List<UserLibrary> libs = resolved.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList());

                    UserLibrary unresolved = libs.stream()
                            .filter(l -> l.version() == null || l.version().isBlank())
                            .findFirst()
                            .orElse(null);
                    if (unresolved != null) {
                        Platform.runLater(() -> {
                            setBusy(false);
                            error("Could not resolve a version for " + unresolved.groupArtifact() + ".");
                        });
                        return;
                    }

                    // The plugins held back go back in as they were, and the SDK pin is left where it is:
                    // neither is this tab's to move.
                    List<UserLibrary> userLibs = new ArrayList<>(libs);
                    userLibs.addAll(heldPlugins);
                    libraryService.updateLibraries(userLibs, libraryService.currentSdkVersion())
                            .whenComplete((ok, err) -> Platform.runLater(() -> {
                                setBusy(false);
                                if (err != null) {
                                    error(rootMessage(err));
                                } else {
                                    statusLabel.setStyle("-fx-text-fill: gray;");
                                    statusLabel.setText("Saved to pom.xml.");
                                    onChanged.run();
                                }
                            }));
                });
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        apply.setDisable(busy);
        revert.setDisable(busy);
        coordinateField.setDisable(busy);
        versionCombo.setDisable(busy);
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
}
