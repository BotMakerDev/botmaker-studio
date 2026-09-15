package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import com.botmaker.studio.services.upgrade.ProjectUpgrade;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.ui.app.upgrade.ReportView;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Project ▸ Upgrade…</b> — every plugin this project installs, with the version it is on and the version
 * it could be on.
 *
 * <p>This is <b>Upgrade SDK…</b> generalised, and the generalisation is the point: plugin #1 got a checked
 * migration and every other plugin got a pom edit, which is exactly the privilege the plugin platform exists
 * to refuse. The SDK appears here as an ordinary row.
 *
 * <h2>What the window is, and what it is not</h2>
 *
 * <p>It is a <b>table of coordinates with two versions each</b>. The version control is a combo box rather
 * than an up arrow, so a <b>downgrade is the same operation</b> — the engine runs with the jars the other way
 * round and reports what an older release lacks, which needs no new code path. Check is per row because the
 * report is per plugin: what a bot calls of plugin A says nothing about plugin B.
 *
 * <p>It is <b>not</b> a second install/remove surface. Adding and removing a plugin is
 * {@link ManagePluginsDialog}'s, and this window changes only versions of things the pom already declares.
 *
 * <h2>One pass, not one pass per row</h2>
 *
 * <p>Applying takes one snapshot, repairs each row in sequence and writes the pom once — and refuses the
 * whole pass if any row cannot be repaired. {@link ProjectUpgrade} owns that rule rather than this class,
 * because it is not a layout decision: a project sitting on plugin A's new version with plugin B's old source
 * compiles against neither.
 *
 * <h2>Two plugins, one simple name</h2>
 *
 * <p>Call sites are attributed by the simple type name the source writes, so two plugins declaring
 * {@code Point} make that call unanswerable. The set of clashing names is computed once, from the installed
 * jars, and handed to every service built here — which then refuses to report rather than guessing. See
 * {@link InstalledPlugin#ambiguousAmong}.
 */
public final class ProjectUpgradeDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    private final GridPane table = new GridPane();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button applyButton = new Button("Snapshot, repair & switch");
    private final ReportView reportView = new ReportView();
    private final List<Row> rows = new ArrayList<>();

    private Stage stage;
    private Row showing;

    public ProjectUpgradeDialog(Window owner, ProjectConfig config, ProjectState state,
                                LibraryService libraryService, PluginRegistry registry,
                                JitPackSearch jitpack) {
        this.owner = owner;
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Upgrade");

        progress.setPrefSize(18, 18);
        progress.setVisible(true);
        applyButton.setDisable(true);
        applyButton.setOnAction(e -> runApply());

        table.setHgap(10);
        table.setVgap(6);
        ColumnConstraints name = new ColumnConstraints();
        name.setHgrow(Priority.ALWAYS);
        table.getColumnConstraints().add(name);

        Label hint = new Label("Every plugin this project's pom declares. Check a row to see what moving it "
                + "would do to this bot; nothing is changed until you apply.");
        hint.setWrapText(true);
        hint.getStyleClass().add("sdk-upgrade-empty");

        ScrollPane reportScroll = new ScrollPane(reportView.node());
        reportScroll.setFitToWidth(true);
        VBox.setVgrow(reportScroll, Priority.ALWAYS);
        reportView.placeholder("Pick a version and press Check on a row.");

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(new HBox(8, hint, progress), table, reportScroll, statusLabel,
                buttonBar());

        stage.setScene(ThemedWindows.scene(root, 760, 620));
        stage.show();
        load();
    }

    private Node buttonBar() {
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, spacer, applyButton, close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
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
        status("Reading this project's plugins…");
        registry.browse().thenAccept(entries -> {
            List<InstalledPlugin> found = InstalledPlugin.of(
                    libraryService.declaredLibraries(), entries, MavenService.localPluginBuilds(),
                    InstalledPlugin.jarDeclaresPlugin(config.projectPath()));
            Set<String> ambiguous = InstalledPlugin.ambiguousAmong(config.projectPath(), found);
            Platform.runLater(() -> render(found, ambiguous));
        }).exceptionally(error -> {
            Platform.runLater(() -> {
                progress.setVisible(false);
                status("Could not read this project's plugins: " + error.getMessage());
            });
            return null;
        });
    }

    private void render(List<InstalledPlugin> found, Set<String> ambiguous) {
        progress.setVisible(false);
        table.getChildren().clear();
        rows.clear();

        if (found.isEmpty()) {
            status("");
            table.add(new Label("This project declares no plugins, so there is nothing to upgrade. "
                    + "Project ▸ Manage Plugins… is where one is installed."), 0, 0, 5, 1);
            return;
        }

        status("");
        int line = 0;
        table.add(heading("Plugin"), 0, line);
        table.add(heading("Installed"), 1, line);
        table.add(heading("Move to"), 2, line);
        line++;

        for (InstalledPlugin plugin : found) {
            Row row = new Row(plugin, ambiguous);
            rows.add(row);
            table.add(row.name, 0, line);
            table.add(row.installed, 1, line);
            table.add(row.versions, 2, line);
            table.add(row.check, 3, line);
            table.add(row.verdict, 4, line);
            line++;
            row.loadVersions();
        }
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    /** One plugin: its two versions, its own Check, and the report that Check produced. */
    private final class Row {

        private final InstalledPlugin plugin;
        private final PluginUpgradeService upgrades;

        private final Label name;
        private final Label installed;
        private final ComboBox<String> versions = new ComboBox<>();
        private final Button check = new Button("Check");
        private final Label verdict = new Label();

        private Report report;

        Row(InstalledPlugin plugin, Set<String> ambiguous) {
            this.plugin = plugin;
            this.upgrades = new PluginUpgradeService(config, state, libraryService, jitpack,
                    plugin.artifact(), ambiguous);

            name = new Label(plugin.displayName()
                    + (plugin.source() == InstalledPlugin.Source.LOCAL_BUILD ? "   (local build)" : ""));
            installed = new Label(plugin.installed());
            installed.getStyleClass().add("sdk-upgrade-detail");

            versions.setPrefWidth(190);
            versions.setPromptText("loading versions…");
            versions.setDisable(true);
            // A version change invalidates the verdict beside it: a chip that still says "nothing breaks"
            // about the version the user has just moved away from is worse than no chip.
            versions.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
                report = null;
                verdict.setText("");
                refreshApply();
            });

            check.setOnAction(e -> runCheck());
            check.setDisable(true);
            verdict.setWrapText(true);
        }

        /**
         * Fills the combo box.
         *
         * <p>Seeded with what is already known — the registry's verified version, or the local build — so a
         * row is usable before JitPack answers and stays usable if it never does. The list itself is every
         * version JitPack can build, which is what makes a <b>downgrade</b> reachable: the versions below the
         * installed one are in the same menu as the ones above it.
         */
        void loadVersions() {
            String seed = plugin.available().isBlank() ? plugin.installed() : plugin.available();
            versions.getItems().setAll(seed);
            versions.getSelectionModel().select(seed);
            versions.setDisable(false);
            versions.setPromptText(null);
            check.setDisable(false);

            upgrades.availableVersions().thenAccept(fetched -> Platform.runLater(() -> {
                if (fetched.isEmpty()) return;               // offline: the seed is still a real answer
                String selected = versions.getValue();
                List<String> items = new ArrayList<>(fetched);
                if (!items.contains(seed)) items.add(seed);
                versions.getItems().setAll(items);
                versions.getSelectionModel().select(items.contains(selected) ? selected : seed);
            }));
        }

        /** The report for this row alone, read off the FX thread and shown below the table. */
        void runCheck() {
            String target = versions.getValue();
            if (target == null || target.isBlank()) return;
            check.setDisable(true);
            progress.setVisible(true);
            status("Resolving and scanning " + plugin.displayName() + " " + target + "…");

            Thread worker = new Thread(() -> {
                Report result;
                try {
                    result = upgrades.compare(target, false);
                } catch (RuntimeException e) {
                    String message = e.getMessage();
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        check.setDisable(false);
                        verdict.setText("could not check");
                        status("The check for " + plugin.displayName() + " failed: " + message);
                    });
                    return;
                }
                Report done = result;
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    check.setDisable(false);
                    report = done;
                    verdict.setText(chip(done));
                    showing = Row.this;
                    reportView.render(done, ReportView.Mode.UPGRADE);
                    status("");
                    refreshApply();
                });
            }, "plugin-upgrade-check");
            worker.setDaemon(true);
            worker.start();
        }

        /** Whether this row is asking for anything at all. A row on its installed version is not. */
        boolean isMoving() {
            String target = versions.getValue();
            return target != null && !target.isBlank() && !target.equals(upgrades.currentVersion());
        }

        /** True when the row has been checked and the pass would refuse it. */
        boolean isBlocked() {
            return report != null && (report.isIncomplete() || !report.unrepairable().isEmpty());
        }

        ProjectUpgrade.Row asRow() {
            return new ProjectUpgrade.Row(upgrades, versions.getValue(), false,
                    showing == this ? reportView.picks() : Map.of());
        }
    }

    /**
     * The row's one-line verdict.
     *
     * <p>Three states and no fourth, because the three are what the apply button does: nothing to do, work
     * Studio will do, and work it refuses to do. A count is given where there is one — <i>2 repairable</i>
     * says something <i>changes will be made</i> does not.
     */
    private static String chip(Report r) {
        if (r.isIncomplete()) return "blocked — could not read";
        if (!r.unrepairable().isEmpty()) return "blocked — " + r.unrepairable().size() + " to fix by hand";
        if (r.breaks().isEmpty()) return "nothing breaks";
        return r.breaks().size() + " repairable";
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    /**
     * Enabled when something is being asked for and nothing already checked would refuse.
     *
     * <p>A row nobody has checked does not disable it: {@link ProjectUpgrade} checks every row again before
     * writing anything, so an unchecked row that turns out to be blocked refuses the pass with the project
     * untouched. Requiring a Check per row would make the button read as broken on a project where every row
     * is fine.
     */
    private void refreshApply() {
        boolean moving = rows.stream().anyMatch(Row::isMoving);
        boolean blocked = rows.stream().anyMatch(Row::isBlocked);
        applyButton.setDisable(!moving || blocked);
    }

    private void runApply() {
        List<ProjectUpgrade.Row> moving = rows.stream().filter(Row::isMoving).map(Row::asRow).toList();
        if (moving.isEmpty()) return;

        applyButton.setDisable(true);
        progress.setVisible(true);
        status("Committing a snapshot, repairing your call sites and switching " + moving.size()
                + " plugin(s)…");

        ProjectUpgrade.run(moving, libraryService::updateVersions)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    progress.setVisible(false);
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        status("");
                        ThemedWindows.alert(Alert.AlertType.ERROR,
                                "The upgrade did not run:\n\n" + cause.getMessage()).showAndWait();
                        applyButton.setDisable(false);
                        return;
                    }
                    status("Done. The previous state is one revert away in Project History.");
                    stage.close();
                }));
    }

    private void status(String message) {
        statusLabel.setText(message == null ? "" : message);
    }
}
