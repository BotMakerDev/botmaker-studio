package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>Installed</b> tab of <b>Project ▸ Plugins &amp; Libraries…</b> — every plugin this project installs,
 * with the version it is on and the version it could be on.
 *
 * <p>This was the <b>Upgrade…</b> window until 2026-09-29, one of five Project-menu entries about the same
 * pom; it is a tab of {@link PluginsWindow} now, beside Browse and Libraries. It is <b>Upgrade SDK…</b>
 * generalised, and the generalisation is the point: plugin #1 got a checked migration and every other plugin
 * got a pom edit, which is exactly the privilege the plugin platform exists to refuse. The SDK appears here
 * as an ordinary row, and this is the only place any plugin's version is changed — Libraries holds plugin
 * rows back and Browse offers no Remove, so no plugin moves without its report.
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
 * writes <em>down</em> is left as written, marked and listed, because a declaration has no value to stand in
 * for. It refused the removal until 2026-09-29; nothing here refuses now.
 *
 * <p><b>Install is the one operation with no report</b>, and needs none: nothing is migrated by adding a
 * dependency. It is {@link BrowsePluginsTab}'s — the catalogue, the descriptions and the editor dependencies
 * live there — and this tab's <i>Add a plugin…</i> switches to it.
 *
 * <h2>One pass, not one pass per row</h2>
 *
 * <p>Applying takes one snapshot, repairs each row in sequence and writes the pom once. {@link ProjectUpgrade}
 * owns that rule rather than this class, because it is not a layout decision: a project sitting on plugin A's
 * new version with plugin B's old source compiles against neither. Nothing a check finds refuses the pass
 * (2026-09-29); what cannot be repaired is left as written, marked and listed.
 *
 * <h2>Two plugins, one simple name</h2>
 *
 * <p>Call sites are attributed by the simple type name the source writes, so two plugins declaring
 * {@code Point} make that call unanswerable. The set of clashing names is computed once, from the installed
 * jars, and handed to every service built here — which leaves those names out rather than guessing. See
 * {@link InstalledPlugin#ambiguousAmong}.
 */
public final class InstalledPluginsTab {

    /** The state cell's base style class; {@code CHIP + "-ok"} and friends carry the colour. */
    private static final String CHIP = "upgrade-chip";

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

    private final Runnable onAddPlugin;
    private final Runnable onOpenReview;
    private final Runnable onChanged;
    private final VBox root = new VBox(12);
    private Row showing;

    /**
     * @param onAddPlugin  <i>Add a plugin…</i>: the window switches to Browse
     * @param onOpenReview the result block's <i>Open Review tab</i>; the tab is the shell's, so the shell
     *                     supplies the verb, and the window closes behind it
     * @param onChanged    after a pass or a removal wrote the pom, so the window's other tabs read it again
     */
    InstalledPluginsTab(ProjectConfig config, ProjectState state, LibraryService libraryService,
                        PluginRegistry registry, JitPackSearch jitpack,
                        Runnable onAddPlugin, Runnable onOpenReview, Runnable onChanged) {
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.registry = registry;
        this.jitpack = jitpack;
        this.onAddPlugin = onAddPlugin;
        this.onOpenReview = onOpenReview;
        this.onChanged = onChanged;
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
        applyButton.setDisable(true);
        applyButton.setOnAction(e -> runApply());
        reportView.onPicked(this::refreshApply);

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
        openReview.setOnAction(e -> onOpenReview.run());
        resultBox.getStyleClass().add("sdk-upgrade-card");
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        root.setPadding(new Insets(12, 0, 0, 0));
        root.getChildren().addAll(new HBox(8, hint, progress), table, reportScroll, resultBox, statusLabel,
                whyDisabled, buttonBar());
        load();
    }

    private Node buttonBar() {
        // Install has no report and needs none, so it is a door to the catalogue rather than a fifth control
        // here: the descriptions, the editor dependencies and the id check all already live there.
        Button add = new Button("Add a plugin…");
        add.setOnAction(e -> onAddPlugin.run());

        // Every row to the version the registry verified (or the local build), each checked as it moves —
        // the one click the "Update plugins…" banner leads to.
        Button updateAll = new Button("Update all");
        updateAll.setOnAction(e -> rows.forEach(Row::selectRecommended));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, add, spacer, updateAll, applyButton);
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
        /** A removal report on screen that waits for picks; the next Remove… confirms it instead of re-checking. */
        private Report pendingRemoval;
        /** True while {@link #loadVersions} is filling the combo box, so seeding checks nothing. */
        private boolean seeding = true;
        private boolean checking;

        Row(InstalledPlugin plugin, Set<String> ambiguous) {
            this.plugin = plugin;
            this.upgrades = new PluginUpgradeService(config, state, libraryService, jitpack,
                    plugin.artifact(), ambiguous);

            name = new Label(plugin.displayName()
                    + (plugin.source() == InstalledPlugin.Source.LOCAL_BUILD ? "   (local build)" : ""));
            // Resolved: a template pins its SDK as ${botmaker.sdk.version}, and the pom's text is not a version.
            installed = new Label(upgrades.currentVersion());
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
                pendingRemoval = null;
                chip("", "none");
                refreshApply();
                if (!seeding && isMoving()) runCheck();
            });

            check.setOnAction(e -> runCheck());
            check.setDisable(true);
            remove.setOnAction(e -> {
                if (pendingRemoval != null && showing == this) confirmRemoval(pendingRemoval);
                else runRemovalCheck();
            });
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
        /** The version this row is seeded with: the registry's verified one, the local build, or the installed. */
        String recommended() {
            return plugin.available().isBlank() ? upgrades.currentVersion() : plugin.available();
        }

        /** <i>Update all</i> for this row: the recommended version, checked when that is a move. */
        void selectRecommended() {
            if (versions.isDisable()) return;
            String seed = recommended();
            if (!seed.equals(versions.getValue())) {
                versions.getSelectionModel().select(seed);  // the listener checks it
            } else if (isMoving() && report == null && !checking) {
                runCheck();
            }
        }

        void loadVersions() {
            seeding = true;
            String seed = recommended();
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
            pendingRemoval = null;
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
            // Nothing here refuses since 2026-09-29: what could not be read, a type the bot writes down, and a
            // call nobody chose for are each said, then left as written or defaulted and marked.
            pendingRemoval = null;
            String what = r.breaks().isEmpty()
                    ? "This bot calls nothing in it, so only the pom changes."
                    : r.breaks().size() + " call(s) will be replaced by a default value or deleted, and the "
                    + "functions they are in will be marked for review.";
            if (!r.leftForYou().isEmpty()) {
                what += "\n\n" + r.leftForYou().stream().map(Break::type).distinct().count()
                        + " type(s) this bot writes down will be left as written and marked, for you to change: "
                        + String.join(", ", r.leftForYou().stream().map(Break::type).distinct().toList()) + ".";
            }
            if (reportView.unpicked() > 0) what += "\n\n" + unpickedReason(reportView.unpicked());
            if (r.isIncomplete()) what += "\n\nNot everything could be read: " + r.problems().getFirst();
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
                    .whenComplete((repaired, error) -> Platform.runLater(() -> {
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
                        showResult(removalSummary(plugin.displayName(), repaired.files(), r.breaks().size(),
                                repaired.leftAsWritten()), repaired.files() > 0);
                        // The table it was a row of is now wrong, and the clash set with it: one plugin
                        // fewer can make a name that was ambiguous answerable again.
                        reload();
                        onChanged.run();
                    }));
        }

        /** Whether this row is asking for anything at all. A row on its installed version is not. */
        boolean isMoving() {
            String target = versions.getValue();
            return target != null && !target.isBlank() && !target.equals(upgrades.currentVersion());
        }

        ProjectUpgrade.Row asRow() {
            return new ProjectUpgrade.Row(upgrades, versions.getValue(), false,
                    showing == this ? reportView.picks() : Map.of());
        }
    }

    /**
     * The row's one-line verdict. None of them stops the move since 2026-09-29: <i>to finish by hand</i> and
     * <i>partly checked</i> say what the user will be left with, not that the button refuses.
     */
    static String chipText(Report r) {
        if (!r.leftForYou().isEmpty()) {
            return r.breaks().size() + " repairable, " + r.leftForYou().size() + " to finish by hand";
        }
        if (r.isIncomplete()) {
            return r.breaks().isEmpty() ? "partly checked" : r.breaks().size() + " repairable, partly checked";
        }
        if (r.breaks().isEmpty()) return "nothing breaks";
        return r.breaks().size() + " repairable";
    }

    /**
     * The removal's own result sentence — the upgrade's {@link ProjectUpgrade.Result#summary()} for the one
     * operation that has no versions to name.
     */
    static String removalSummary(String plugin, int filesRewritten, int calls) {
        return removalSummary(plugin, filesRewritten, calls, List.of());
    }

    /** As above, with what the repair left as written. */
    static String removalSummary(String plugin, int filesRewritten, int calls, List<String> leftForYou) {
        String head = "Removed " + plugin + " from this project.";
        String left = leftForYou.isEmpty() ? "" : " " + leftForYou.size()
                + (leftForYou.size() == 1 ? " thing was" : " things were") + " left for you to finish: "
                + String.join(" ", leftForYou);
        if (filesRewritten == 0) return head + " This bot called nothing in it, so only the pom changed." + left
                + " The previous state is one restore away in the Versions tab.";
        return head + " " + calls + " call" + (calls == 1 ? "" : "s") + " replaced or deleted in "
                + filesRewritten + " file" + (filesRewritten == 1 ? "" : "s")
                + " — the functions they are in are marked for review." + left
                + " The previous state is one restore away in the Versions tab.";
    }

    /** The states as a style class, so the colour is the stylesheet's and not this file's. */
    static String chipState(Report r) {
        if (!r.leftForYou().isEmpty() || r.isIncomplete()) return "partial";
        return r.breaks().isEmpty() ? "ok" : "repairable";
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    /**
     * Enabled whenever something is being asked for and no check is running. Nothing a check finds disables
     * it since 2026-09-29 — an upgrade is never blocked. A call waiting for a pick is said beside the button,
     * because pressing Apply gives it a default value and a review mark.
     */
    private void refreshApply() {
        int moving = (int) rows.stream().filter(Row::isMoving).count();
        boolean checking = rows.stream().anyMatch(r -> r.checking);
        // Only the report on screen can have sites waiting, and only a moving row's report counts.
        int unpicked = showing != null && showing.report != null && showing.isMoving() ? reportView.unpicked() : 0;
        applyButton.setDisable(moving == 0 || checking);

        String why = applyBlockedReason(moving, checking, unpicked);
        whyDisabled.setText(why);
        whyDisabled.setVisible(!why.isEmpty());
        whyDisabled.setManaged(!why.isEmpty());
    }

    /**
     * What the line beside Apply says, or "" for nothing: why it is grey (no row moves, a check is running),
     * or what pressing it will do to the calls still waiting for a pick.
     *
     * <p>A disabled button is a statement the user cannot read: they see that BotMaker will not do the thing
     * and are left to guess whether the window is broken. Each sentence names the <em>next action</em>, which
     * is the only part they can act on.
     */
    static String applyBlockedReason(int movingRows, boolean checking, int unpicked) {
        if (movingRows == 0) {
            return "Pick a version different from the installed one on at least one row.";
        }
        if (checking) return "A check is still running.";
        if (unpicked > 0) return unpickedReason(unpicked);
        return "";
    }

    /**
     * What happens to a call with nothing that fits: the user may choose, and one left unchosen gets a default
     * value and a review mark rather than holding the upgrade.
     */
    static String unpickedReason(int unpicked) {
        return unpicked + (unpicked == 1 ? " call has" : " calls have") + " nothing that replaces it. Choose in "
                + "the report below whether each becomes a default value or is deleted; any left unchosen gets "
                + "a default value and is marked for review.";
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
