package com.botmaker.studio.ui.app.overlay;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The panel's header: what it is docked beside and how big that is, ⇄ Change to watch something else, and the
 * run — its bar while the bot runs, ▶ Run otherwise: the bot, or only the activity the script shows.
 *
 * <p>The run bar is the run overlay's own ({@code RunBarDock}): while the panel is open the bar is drawn in
 * {@link #runSlot()} instead of in a window of its own, so there is one set of run controls on screen.
 *
 * <p>The title row is the drag handle; dragging the panel off its window un-docks it until ⇲ Dock.
 */
final class PanelHeader {

    /**
     * @param onChange ⇄ Change: watch another screen
     * @param onDock   ⇲ Dock: back beside the watched screen
     * @param onRun    ▶ Run the bot
     * @param onRunTarget ▶ Run this activity: the target shown, on its own
     * @param onClose  ✕
     */
    record Callbacks(Runnable onChange, Runnable onDock, Runnable onRun, Runnable onRunTarget, Runnable onClose) {}

    private final VBox node;
    private final HBox titleRow;
    private final Label watched = OverlayStyles.dimLabel("");
    private final Button dock;
    private final StackPane runSlot = new StackPane();
    private final MenuItem runTarget = new MenuItem();

    PanelHeader(Callbacks callbacks) {
        Label title = new Label("Overlay editor");
        title.setStyle("-fx-text-fill: #c9d4e6; -fx-font-weight: bold;");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        dock = OverlayStyles.iconButton("⇲", "Dock beside the watched screen again", callbacks.onDock());
        showDock(false);
        Button close = OverlayStyles.iconButton("✕", "Close the overlay editor", callbacks.onClose());
        titleRow = new HBox(8, title, spring, dock, close);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button change = new Button("⇄ Change");
        change.setTooltip(new Tooltip("Watch another window — the one the bot looks at"));
        change.setOnAction(e -> callbacks.onChange().run());
        watched.setMinWidth(0);
        HBox.setHgrow(watched, Priority.ALWAYS);
        HBox watchedRow = new HBox(6, watched, change);
        watchedRow.setAlignment(Pos.CENTER_LEFT);

        MenuItem runBot = new MenuItem("Run the bot");
        runBot.setOnAction(e -> callbacks.onRun().run());
        runTarget.setOnAction(e -> callbacks.onRunTarget().run());
        showTarget(null);
        MenuButton run = new MenuButton("▶ Run", null, runTarget, runBot);
        run.setTooltip(new Tooltip("Run the bot, or only the activity shown; its controls show here while it runs"));
        runSlot.setAlignment(Pos.CENTER_LEFT);
        // ▶ while nothing runs; the run bar takes its place while something does.
        HBox runRow = new HBox(6, run, runSlot);
        HBox.setHgrow(runSlot, Priority.ALWAYS);
        runSlot.getChildren().addListener((ListChangeListener<javafx.scene.Node>) c -> {
            boolean running = !runSlot.getChildren().isEmpty();
            run.setVisible(!running);
            run.setManaged(!running);
        });

        node = new VBox(6, titleRow, watchedRow, runRow);
        node.setPadding(new Insets(6, 8, 6, 10));
        node.setStyle(OverlayStyles.PANEL);
    }

    VBox node() {
        return node;
    }

    /** The drag handle. */
    HBox handle() {
        return titleRow;
    }

    /** Where the run bar is drawn while the panel is open. */
    StackPane runSlot() {
        return runSlot;
    }

    /** Says what the panel is beside: {@code Beside "Game" · ▧ 1280×720}. */
    void showWatched(String label, java.awt.Rectangle bounds) {
        String size = bounds == null ? "not on screen" : "▧ " + bounds.width + "×" + bounds.height;
        watched.setText("Beside " + label + " · " + size);
        watched.setTooltip(new Tooltip("The screen the bot watches: " + label));
    }

    /**
     * Names the activity ▶ Run ▸ Run this activity runs, or disables it: {@code null} when the script shows no
     * target that runs on its own, or no plugin offers a trial entry.
     */
    void showTarget(String label) {
        runTarget.setText(label == null ? "Run this activity" : "Run " + label + " on its own");
        runTarget.setDisable(label == null);
    }

    /** Shows ⇲ Dock while the panel has been dragged off the screen it is docked beside. */
    void showDock(boolean undocked) {
        dock.setVisible(undocked);
        dock.setManaged(undocked);
    }
}
