package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.input.InputListenerFactory;
import com.botmaker.studio.plugin.HostRuns;
import com.botmaker.studio.runtime.StopKeyPreference;
import com.botmaker.studio.ui.app.overlay.OverlayStyles;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The run bar's content for one run: stop, pause, run again and hide; each plugin's bar node; the trace's last
 * lines. One node, so it is drawn wherever the run is followed from — its own small window
 * ({@link RunOverlayWindows}), or the overlay editor's header while that panel is open ({@link RunBarDock}).
 * FX thread.
 */
final class RunBar {

    /** How often the bar reads the run's pause state. */
    private static final Duration TICK = Duration.millis(500);

    private final VBox root;
    private final HBox controls;
    private final VBox trace = new VBox(2);
    private final Timeline keeper;

    RunBar(RunOverlay.Session session, List<Node> pluginNodes) {
        Runs runs = HostRuns.live();
        Label grip = OverlayStyles.dimLabel("⠿");
        Button stop = OverlayStyles.iconButton("⏹", "Stop the bot", session::stop);
        Button pause = OverlayStyles.iconButton("⏸", "Pause the bot", () -> { });
        // Signalled off the FX thread: pausing runs `kill`, which the bar must not wait on.
        pause.setOnAction(e -> {
            pause.setDisable(true);
            Thread.ofVirtual().start(() -> {
                if (runs.isPaused()) runs.resume();
                else runs.pause();
                Platform.runLater(() -> {
                    pause.setDisable(false);
                    refreshPause(pause, runs, session);
                });
            });
        });
        refreshPause(pause, runs, session);
        // Read again while the bar is up: the run's process starts after the bar opens, and a plugin may pause
        // or resume the run itself.
        keeper = new Timeline(new KeyFrame(TICK, e -> refreshPause(pause, runs, session)));
        keeper.setCycleCount(Animation.INDEFINITE);
        keeper.play();
        Button again = OverlayStyles.iconButton("↻", "Stop the bot and run it again", session::runAgain);
        show(again, !session.debugging());
        Button hide = OverlayStyles.iconButton("✕", "Hide until the next run", session::dismiss);

        controls = new HBox(6, grip, stop, pause, again);
        controls.setAlignment(Pos.CENTER_LEFT);
        if (InputListenerFactory.isSupported()) {
            controls.getChildren().add(OverlayStyles.dimLabel(StopKeyPreference.name() + " stops it"));
        }
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        controls.getChildren().addAll(spacer, hide);

        root = new VBox(6, controls);
        if (!pluginNodes.isEmpty()) root.getChildren().add(new HBox(8, pluginNodes.toArray(Node[]::new)));
        show(trace, false);
        root.getChildren().add(trace);
    }

    /** The whole bar. */
    VBox node() {
        return root;
    }

    /** The controls row, which a floating bar is dragged by. */
    HBox handle() {
        return controls;
    }

    /** Shows the trace's tail. */
    void trace(List<TraceLine> tail) {
        List<Node> lines = new ArrayList<>();
        for (TraceLine line : tail) lines.add(traceLabel(line));
        trace.getChildren().setAll(lines);
        show(trace, !lines.isEmpty());
    }

    void close() {
        keeper.stop();
    }

    /** Shows the pause button while the run can be paused or is paused, its glyph saying which click it takes. */
    private static void refreshPause(Button pause, Runs runs, RunOverlay.Session session) {
        boolean paused = runs.isPaused();
        show(pause, !session.debugging() && (paused || runs.canPause()));
        String glyph = paused ? "▶" : "⏸";
        if (!glyph.equals(pause.getText())) {
            pause.setText(glyph);
            pause.setTooltip(new Tooltip(paused ? "Resume the bot" : "Pause the bot"));
        }
    }

    private static Label traceLabel(TraceLine line) {
        String text = line.count() > 1 ? line.text() + " (×" + line.count() + ")" : line.text();
        Label label = new Label(text.replace('\n', ' '));
        // As wide as wherever the bar is drawn, and cut short there.
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        label.setStyle(switch (line.level()) {
            case ERROR -> "-fx-text-fill: #ff7b72;";
            case WARN -> "-fx-text-fill: #e3b341;";
            case DEBUG -> OverlayStyles.DIM_LABEL;
            case INFO, UNKNOWN -> OverlayStyles.LABEL;
        });
        label.setTooltip(new Tooltip(line.text()));
        return label;
    }

    static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
