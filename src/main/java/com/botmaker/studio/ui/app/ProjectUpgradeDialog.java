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
import javafx.scene.control.ButtonType;
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
 * to refuse. The SDK appears here as an ordinary row. Since 2026-09-19 it is the <b>only</b> door: the two
 * pre-chosen variants of it in the Project menu are gone.
 *
 * <h2>Saying what it is doing</h2>
 *
 * <p>Three rules, all learned from a window that did the work and said nothing about it. Picking a version
 * <b>runs that row's check by itself</b>, because a user who has chosen a version has already asked the
 * question the Check button repeats. Every row carries its <b>own</b> state chip rather than sharing one
 * spinner. And Apply <b>says why it is disabled</b>, since a dead button with no sentence beside it reads as
 * a broken window rather than as a missing step.
 *
 * <p>On success the window <b>stays open</b> with what the pass did — versions moved, files rewritten, calls
 * repaired, and the way back. Closing on success threw away the one message worth reading.
 *
 * <h2>What the window is, and what it is not</h2>
 *
 * <p>It is a <b>table of coordinates with two versions each</b>. The version control is a combo box rather
 * than an up arrow, so a <b>downgrade is the same operation</b> — the engine runs with the jars the other way
 * round and reports what an older release lacks, which needs no new code path. Check is per row because the
 * report is per plugin: what a bot calls of plugin A says nothing about plugin B.
 *
 * <h2>Four operations, one report</h2>
 *
 * <p>Upgrade, downgrade, remove and install. The first two are the same control and the same engine run with
 * the jars in a different order. <b>Remove is the same report with no target jar at all</b> — every call into
 * the plugin becomes a default value or a deleted line, every import of it is dropped, and a type the bot
 * writes <em>down</em> refuses the removal by name, because a declaration has no value to stand in for. That
 * refusal is the maintainer's constraint working rather than a gap: no operation here may leave the project
 * with a compilation error.
 *
 * <p><b>Install is the one operation with no report</b>, and needs none: nothing is migrated by adding a
 * dependency. It stays {@link ManagePluginsDialog}'s — the catalogue, the descriptions and the editor
 * dependencies live there — and is reached from this window's own button, so a user deciding what this
 * project should run does it in one place.
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

    /** The state cell's base style class; {@code CHIP + "-ok"} and friends carry the colour. */
    private static final String CHIP = "upgrade-chip";

    private final Window owner;
    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    private final GridPane table = new GridPane();
    private final Label statusLabel = new Label();
    private final Label whyDisabled = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button applyButton = new Button("Snapshot, repair & switch");
    private final ReportView reportView = new ReportView();
    private final List<Row> rows = new ArrayList<>();

    /** The success block, hidden until there is one. See {@link #showResult}. */
    private final VBox resultBox = new VBox(6);
    private final Label resultText = new Label();
    private final Button openReview = new Button("Open Review tab");

    private Stage stage;
    private Row showing;
    private Runnable onOpenReview;

    /**
     * How the result block raises the Review tab. The tab is the shell's, so the shell supplies the verb —
     * a dialog that opened it itself would be a second implementation of the menu entry.
     */
    public void setOnOpenReview(Runnable openReviewTab) {
        this.onOpenReview = openReviewTab;
    }

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

        whyDisabled.setWrapText(true);
        whyDisabled.getStyleClass().add("sdk-upgrade-empty");

        resultText.setWrapText(true);
        openReview.setOnAction(e -> {
            if (onOpenReview != null) onOpenReview.run();
            stage.close();
        });
        resultBox.getStyleClass().add("sdk-upgrade-card");
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(new HBox(8, hint, progress), table, reportScroll, resultBox, statusLabel,
                whyDisabled, buttonBar());

        stage.setScene(ThemedWindows.scene(root, 760, 620));
        stage.show();
        load();
    }

    private Node buttonBar() {
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        // Install has no report and needs none, so it is a door to the catalogue rather than a fifth control
        // here: the descriptions, the editor dependencies and the id check all already live there.
        Button add = new Button("Add a plugin…");
        add.setOnAction(e -> {
            stage.close();
            new ManagePluginsDialog(owner, libraryService, registry, jitpack).show();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, add, spacer, applyButton, close);
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
                    + "\"Add a plugin…\" below is where one is installed."), 0, 0, 6, 1);
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
            table.add(row.remove, 4, line);
            table.add(row.verdict, 5, line);
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
        private final Button remove = new Button("Remove…");
        private final Label verdict = new Label();

        private Report report;
        /** True while {@link #loadVersions} is filling the combo box, so seeding checks nothing. */
        private boolean seeding = true;
        private boolean checking;

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
            // about the version the user has just moved away from is worse than no chip. Choosing one then
            // runs the check, because choosing a version IS asking what it would do — the button stays for
            // re-runs, and the seeding pass is exempt so opening the window fires nothing.
            versions.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
                report = null;
                chip("", "none");
                refreshApply();
                if (!seeding && isMoving()) runCheck();
            });

            check.setOnAction(e -> runCheck());
            check.setDisable(true);
            remove.setOnAction(e -> runRemovalCheck());
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
            seeding = true;
            String seed = plugin.available().isBlank() ? plugin.installed() : plugin.available();
            versions.getItems().setAll(seed);
            versions.getSelectionModel().select(seed);
            versions.setDisable(false);
            versions.setPromptText(null);
            check.setDisable(false);
            seeding = false;

            upgrades.availableVersions().thenAccept(fetched -> Platform.runLater(() -> {
                if (fetched.isEmpty()) return;               // offline: the seed is still a real answer
                String selected = versions.getValue();
                List<String> items = new ArrayList<>(fetched);
                if (!items.contains(seed)) items.add(seed);
                seeding = true;
                versions.getItems().setAll(items);
                versions.getSelectionModel().select(items.contains(selected) ? selected : seed);
                seeding = false;
            }));
        }

        /** Repaints this row's state cell. One class per state, so both themes are the stylesheet's job. */
        private void chip(String text, String state) {
            verdict.getStyleClass().removeIf(c -> c.startsWith(CHIP));
            if (!text.isEmpty()) verdict.getStyleClass().addAll(CHIP, CHIP + "-" + state);
            verdict.setText(text);
        }

        /** The report for this row alone, read off the FX thread and shown below the table. */
        void runCheck() {
            String target = versions.getValue();
            if (target == null || target.isBlank()) return;
            check.setDisable(true);
            checking = true;
            chip("checking…", "checking");
            progress.setVisible(true);
            status("Resolving and scanning " + plugin.displayName() + " " + target + "…");
            refreshApply();

            Thread worker = new Thread(() -> {
                Report result;
                try {
                    result = upgrades.compare(target, false);
                } catch (RuntimeException e) {
                    String message = e.getMessage();
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        check.setDisable(false);
                        checking = false;
                        chip("could not check", "blocked");
                        status("The check for " + plugin.displayName() + " failed: " + message);
                        refreshApply();
                    });
                    return;
                }
                Report done = result;
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    check.setDisable(false);
                    checking = false;
                    report = done;
                    chip(chipText(done), chipState(done));
                    showing = Row.this;
                    reportView.render(done, ReportView.Mode.UPGRADE);
                    // The outcome stays on the status line instead of being wiped: the chip is four words
                    // and the line is where the version that was checked is named.
                    status("Checked " + plugin.displayName() + " " + target + ": " + chipText(done) + ".");
                    refreshApply();
                });
            }, "plugin-upgrade-check");
            worker.setDaemon(true);
            worker.start();
        }

        /**
         * The removal pre-flight: the same report with no target jar, shown before anything is asked.
         *
         * <p>It is never one button. The user reads what removing the plugin would rewrite, and only then
         * confirms — because the rewrite is destructive in a way an upgrade is not: a call that used to do
         * something becomes a default value or disappears, and no later version will bring it back.
         */
        void runRemovalCheck() {
            remove.setDisable(true);
            progress.setVisible(true);
            chip("checking…", "checking");
            status("Reading what removing " + plugin.displayName() + " would break…");

            Thread worker = new Thread(() -> {
                Report result;
                try {
                    result = upgrades.removal();
                } catch (RuntimeException e) {
                    String message = e.getMessage();
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        remove.setDisable(false);
                        chip("could not check", "blocked");
                        status("The check for removing " + plugin.displayName() + " failed: " + message);
                    });
                    return;
                }
                Report done = result;
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    remove.setDisable(false);
                    report = null;                           // this one is not about a version change
                    chip(chipText(done), chipState(done));
                    showing = Row.this;
                    reportView.render(done, ReportView.Mode.REMOVAL);
                    status("Removing " + plugin.displayName() + ": " + chipText(done) + ".");
                    confirmRemoval(done);
                });
            }, "plugin-removal-check");
            worker.setDaemon(true);
            worker.start();
        }

        /** The report is on screen; this is the question that follows it. */
        private void confirmRemoval(Report r) {
            if (r.isIncomplete()) {
                ThemedWindows.alert(Alert.AlertType.ERROR, "Some of this project could not be read, so what "
                        + "removing " + plugin.displayName() + " would break cannot be told:\n\n"
                        + r.problems().getFirst()).showAndWait();
                return;
            }
            if (!r.unrepairable().isEmpty()) {
                ThemedWindows.alert(Alert.AlertType.ERROR, "\"" + r.unrepairable().getFirst().type()
                        + "\" is written down in this bot, not only called — so removing "
                        + plugin.displayName() + " would leave a type name with nothing behind it. The "
                        + "report below lists every place. Change those first.").showAndWait();
                return;
            }

            String what = r.breaks().isEmpty()
                    ? "This bot calls nothing in it, so only the pom changes."
                    : r.breaks().size() + " call(s) will be replaced by a default value or deleted, and the "
                    + "functions they are in will be marked for review.";
            Alert ask = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                    "Remove " + plugin.displayName() + " from this project?\n\n" + what
                            + "\n\nA version of the project is saved first.");
            if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            runRemoval(r);
        }

        private void runRemoval(Report r) {
            remove.setDisable(true);
            progress.setVisible(true);
            status("Committing a snapshot, repairing your call sites and removing "
                    + plugin.displayName() + "…");

            upgrades.remove(plugin.editorDependencies(), !r.breaks().isEmpty(), reportView.picks())
                    .whenComplete((files, error) -> Platform.runLater(() -> {
                        progress.setVisible(false);
                        remove.setDisable(false);
                        if (error != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            status("");
                            ThemedWindows.alert(Alert.AlertType.ERROR,
                                    "The removal did not run:\n\n" + cause.getMessage()).showAndWait();
                            return;
                        }
                        status("");
                        showResult(removalSummary(plugin.displayName(), files, r.breaks().size()), files > 0);
                        // The table it was a row of is now wrong, and the clash set with it: one plugin
                        // fewer can make a name that was ambiguous answerable again.
                        progress.setVisible(true);
                        load();
                    }));
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
    static String chipText(Report r) {
        if (r.isIncomplete()) return "blocked — could not read";
        if (!r.unrepairable().isEmpty()) return "blocked — " + r.unrepairable().size() + " to fix by hand";
        if (r.breaks().isEmpty()) return "nothing breaks";
        return r.breaks().size() + " repairable";
    }

    /**
     * The removal's own result sentence — the upgrade's {@link ProjectUpgrade.Result#summary()} for the one
     * operation that has no versions to name.
     */
    static String removalSummary(String plugin, int filesRewritten, int calls) {
        String head = "Removed " + plugin + " from this project.";
        if (filesRewritten == 0) return head + " This bot called nothing in it, so only the pom changed."
                + " The previous state is one restore away in the Versions tab.";
        return head + " " + calls + " call" + (calls == 1 ? "" : "s") + " replaced or deleted in "
                + filesRewritten + " file" + (filesRewritten == 1 ? "" : "s")
                + " — the functions they are in are marked for review."
                + " The previous state is one restore away in the Versions tab.";
    }

    /** The same three states as a style class, so the colour is the stylesheet's and not this file's. */
    static String chipState(Report r) {
        if (r.isIncomplete() || !r.unrepairable().isEmpty()) return "blocked";
        return r.breaks().isEmpty() ? "ok" : "repairable";
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
        int moving = (int) rows.stream().filter(Row::isMoving).count();
        List<String> blocked = rows.stream().filter(Row::isBlocked).map(r -> r.plugin.displayName()).toList();
        boolean checking = rows.stream().anyMatch(r -> r.checking);
        applyButton.setDisable(moving == 0 || !blocked.isEmpty() || checking);

        String why = applyBlockedReason(moving, blocked, checking);
        whyDisabled.setText(why);
        whyDisabled.setVisible(!why.isEmpty());
        whyDisabled.setManaged(!why.isEmpty());
    }

    /**
     * Why Apply is grey, or "" when it is not.
     *
     * <p>A disabled button is a statement the user cannot read: they see that BotMaker will not do the thing
     * and are left to guess whether the window is broken. Each sentence names the <em>next action</em>, which
     * is the only part they can act on.
     */
    static String applyBlockedReason(int movingRows, List<String> blockedRows, boolean checking) {
        if (movingRows == 0) {
            return "Pick a version different from the installed one on at least one row.";
        }
        if (checking) return "A check is still running.";
        if (!blockedRows.isEmpty()) {
            return String.join(", ", blockedRows) + (blockedRows.size() == 1 ? " is" : " are")
                    + " blocked: the report below says what to change by hand. Setting that row back to its "
                    + "installed version lets the others move.";
        }
        return "";
    }

    private void runApply() {
        List<ProjectUpgrade.Row> moving = rows.stream().filter(Row::isMoving).map(Row::asRow).toList();
        if (moving.isEmpty()) return;

        applyButton.setDisable(true);
        progress.setVisible(true);
        hideResult();
        status("Committing a snapshot, repairing your call sites and switching " + moving.size()
                + " plugin(s)…");

        ProjectUpgrade.run(moving, libraryService::updateVersions)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    progress.setVisible(false);
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        status("");
                        ThemedWindows.alert(Alert.AlertType.ERROR,
                                "The upgrade did not run:\n\n" + cause.getMessage()).showAndWait();
                        applyButton.setDisable(false);
                        return;
                    }
                    status("");
                    showResult(result.summary(), result.touchedSources());
                    // The table is about versions that have just changed, so it is read again — the same
                    // reason a removal reloads it.
                    progress.setVisible(true);
                    load();
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
