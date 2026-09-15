package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import com.botmaker.studio.ui.app.upgrade.ReportView;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Supplier;

/**
 * "What happens to my bot if I move to SDK x.y.z" — asked before anything is changed.
 *
 * <p>Changing the SDK version used to be a cell edit in <b>Manage Libraries</b>: it rewrote the pom and the
 * user found out what that cost by opening their project afterwards. This is the same operation with the
 * answer shown first — {@link PluginUpgradeService} does the reading, this only lays it out.
 *
 * <p><b>The span is split into what Studio can repair and what needs you</b>, because those two lists ask
 * completely different things. The first is a button; the second is reading. Mixing them into one "what
 * changed" list is how a user comes to believe an upgrade was handled when half of it was addressed to them.
 *
 * <p>This replaced a card that printed an {@code mvn rewrite:run} command to paste. The ordering that card
 * had to teach — rewrite first, with the pom still on the old version, then bump — was imposed by
 * OpenRewrite type-attributing against the old SDK, and went away with it (see {@link PluginUpgradeService}).
 *
 * <h2>Two windows, one class</h2>
 *
 * <p>{@link #showModernise()} opens the same dialog on the question that has no version in it: move this bot
 * off what the SDK it <em>already</em> pins has deprecated. There is no combo box and no pom bump; the report
 * is the deprecation list with the SDK's own {@code @ReplacedBy} answers beside it, and the button is the
 * same snapshot-then-rewrite. It shares this class rather than getting one of its own because everything
 * below the top row is the same layout of the same records — a second dialog would be a second place for the
 * repair sentence to drift.
 */
public final class SdkUpgradeDialog {

    private final Window owner;
    private final PluginUpgradeService upgrades;

    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final Button checkButton = new Button("Check");
    private final Button applyButton = new Button("Snapshot & switch");
    private final CheckBox moderniseBox = new CheckBox("Also move off members deprecated on that version");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();

    /**
     * The report itself, laid out — and the one thing that collects rather than displays, the per-call-site
     * answers a split asks for. Shared with the project upgrade window since 2026-09-15, so the two cannot
     * drift on what the repair promises.
     */
    private final ReportView reportView = new ReportView();

    private Stage stage;
    private Report report;
    private boolean modernising;

    public SdkUpgradeDialog(Window owner, PluginUpgradeService upgrades) {
        this.owner = owner;
        this.upgrades = upgrades;
    }

    /** The upgrade: pick a version, read what it costs, switch. */
    public void show() {
        open(false);
    }

    /** The same window with no version in it — move off what the SDK this bot already pins has deprecated. */
    public void showModernise() {
        open(true);
    }

    private void open(boolean moderniseOnly) {
        this.modernising = moderniseOnly;

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(moderniseOnly ? "Modernise" : "Upgrade SDK");

        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        applyButton.setDisable(true);
        applyButton.setText(moderniseOnly ? "Snapshot & modernise" : "Snapshot & switch");

        ScrollPane scroll = new ScrollPane(reportView.node());
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(moderniseOnly ? buildModerniseTopRow() : buildTopRow(), scroll, statusLabel,
                buildButtonBar());

        if (moderniseOnly) {
            placeholder("Reading SDK " + upgrades.currentVersion() + "…");
            runReport(upgrades::modernisations,
                    "Reading what SDK " + upgrades.currentVersion() + " deprecates…");
        } else {
            placeholder("Pick a version and press Check. Nothing is changed until you say so.");
            loadVersions();
        }

        stage.setScene(ThemedWindows.scene(root, 700, 560));
        stage.show();
    }

    // -------------------------------------------------------------------------
    // Chrome
    // -------------------------------------------------------------------------

    private Node buildTopRow() {
        Label current = new Label("This bot is on SDK " + upgrades.currentVersion() + ".");
        current.setStyle("-fx-font-weight: bold;");

        versionCombo.setPrefWidth(180);
        versionCombo.setPromptText("loading versions…");
        versionCombo.setDisable(true);

        checkButton.setDefaultButton(true);
        checkButton.setDisable(true);
        checkButton.setOnAction(e -> runCheck());

        HBox row = new HBox(8, new Label("Upgrade to:"), versionCombo, checkButton, progress);
        row.setAlignment(Pos.CENTER_LEFT);

        // Re-checks rather than only changing what Apply does: the box changes the report as well as the
        // repair, and a checkbox that silently makes the list on screen wrong is worse than a second wait.
        moderniseBox.setOnAction(e -> {
            if (report != null) runCheck();
        });

        return new VBox(8, current, row, moderniseBox);
    }

    /**
     * The modernise header. No version is offered because none is involved — the point of this window is
     * that it moves the bot forward without moving the bot's SDK.
     */
    private Node buildModerniseTopRow() {
        Label current = new Label("This bot is on SDK " + upgrades.currentVersion() + ".");
        current.setStyle("-fx-font-weight: bold;");

        Label what = new Label("Some of what this bot calls is marked deprecated on that same version, and "
                + "the SDK says what to use instead. This moves those calls, without changing the version "
                + "this bot pins.");
        what.setWrapText(true);

        HBox row = new HBox(8, what, progress);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(what, Priority.ALWAYS);

        return new VBox(8, current, row);
    }

    private Node buildButtonBar() {
        applyButton.setOnAction(e -> runApply());

        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, spacer, applyButton, close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    private void loadVersions() {
        upgrades.availableVersions().thenAccept(versions -> Platform.runLater(() -> {
            if (versions.isEmpty()) {
                versionCombo.setPromptText("no versions found — offline?");
                status("Could not reach JitPack, so the list of SDK versions is unavailable.");
                return;
            }
            versionCombo.getItems().setAll(versions);
            versionCombo.setPromptText(null);
            versionCombo.getSelectionModel().selectFirst();
            versionCombo.setDisable(false);
            checkButton.setDisable(false);
        }));
    }

    // -------------------------------------------------------------------------
    // Check
    // -------------------------------------------------------------------------

    private void runCheck() {
        String target = versionCombo.getValue();
        if (target == null || target.isBlank()) return;
        boolean alsoModernise = moderniseBox.isSelected() && !moderniseBox.isDisabled();
        runReport(() -> upgrades.compare(target, alsoModernise),
                "Resolving and scanning SDK " + target + "…");
    }

    /**
     * Runs one blocking read of the jars off the FX thread and lays the answer out. Both entry points come
     * through here, so a failure says the same thing and leaves the same untouched project either way.
     */
    private void runReport(Supplier<Report> work, String busyText) {
        busy(true, busyText);
        applyButton.setDisable(true);
        report = null;

        Thread worker = new Thread(() -> {
            Report result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                result = null;
                String message = e.getMessage();
                Platform.runLater(() -> {
                    busy(false, "The check failed: " + message);
                    placeholder("Nothing was changed.");
                });
            }
            if (result == null) return;
            Report done = result;
            Platform.runLater(() -> {
                report = done;
                busy(false, "");
                render(done);
                applyButton.setText(applyText(done));
                // The switch is always available; modernising is only available when there is something to
                // move, since it is not offered against a version change the user came here to make anyway.
                applyButton.setDisable(modernising && !done.canModernise());
            });
        }, modernising ? "sdk-modernise-check" : "sdk-upgrade-check");
        worker.setDaemon(true);
        worker.start();
    }

    private String applyText(Report r) {
        if (modernising) return "Snapshot & modernise";
        return (r.canMigrate() || (moderniseBox.isSelected() && r.canModernise()))
                ? "Snapshot, repair & switch to " + r.to()
                : "Snapshot & switch to " + r.to();
    }

    /**
     * Lays the report out, and answers the one question that is this dialog's rather than the view's:
     * whether the extra modernise hop is still on offer.
     *
     * <p>A break list that could not be repaired disables it — the migration is one all-or-nothing pass, so
     * offering to modernise inside a pass that will not run is offering nothing.
     */
    private void render(Report r) {
        if (!modernising) {
            moderniseBox.setDisable(r.isIncomplete() || !r.unrepairable().isEmpty());
            if (moderniseBox.isDisabled()) moderniseBox.setSelected(false);
        }
        reportView.render(r, modernising ? ReportView.Mode.MODERNISE : ReportView.Mode.UPGRADE);
    }

    // -------------------------------------------------------------------------
    // Apply
    // -------------------------------------------------------------------------

    private void runApply() {
        if (report == null) return;
        if (modernising) {
            runModernise();
            return;
        }
        String target = report.to();
        boolean repair = report.canMigrate();
        boolean alsoModernise = moderniseBox.isSelected() && !moderniseBox.isDisabled();

        busy(true, repair || alsoModernise
                ? "Committing a snapshot, repairing your call sites and switching to " + target + "…"
                : "Committing a snapshot and switching to " + target + "…");
        applyButton.setDisable(true);

        upgrades.apply(target, repair, alsoModernise, reportView.picks()).whenComplete((ignored, error) ->
                Platform.runLater(() -> {
                    if (error != null) {
                        failed("Could not switch to SDK " + target, error);
                        return;
                    }
                    busy(false, (repair || alsoModernise
                            ? "Now on SDK " + target + ", with your call sites repaired. "
                            : "Now on SDK " + target + ". ")
                            + "The previous state is one revert away in Project History.");
                    stage.close();
                }));
    }

    private void runModernise() {
        busy(true, "Committing a snapshot and moving your calls off what is deprecated…");
        applyButton.setDisable(true);

        upgrades.modernise().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                failed("Could not modernise this bot", error);
                return;
            }
            busy(false, "Your calls have been moved. The SDK version has not changed, and the previous "
                    + "state is one revert away in Project History.");
            stage.close();
        }));
    }

    private void failed(String what, Throwable error) {
        busy(false, "");
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        ThemedWindows.alert(Alert.AlertType.ERROR, what + ":\n\n" + cause.getMessage()).showAndWait();
        applyButton.setDisable(false);
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private void placeholder(String text) {
        reportView.placeholder(text);
    }

    private void busy(boolean running, String message) {
        progress.setVisible(running);
        checkButton.setDisable(running || versionCombo.getValue() == null);
        status(message);
    }

    private void status(String message) {
        statusLabel.setText(message == null ? "" : message);
    }
}
