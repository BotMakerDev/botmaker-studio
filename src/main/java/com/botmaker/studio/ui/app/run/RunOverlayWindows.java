package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.input.InputListenerFactory;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostRuns;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.runtime.StopKeyPreference;
import com.botmaker.studio.ui.app.overlay.OverlayStyles;
import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The run overlay's windows for one run: the <b>bar</b>, a small window the user places, with the controls,
 * the trace's last lines and each plugin's bar node; and the <b>layer</b>, a transparent window over the whole
 * desktop holding each plugin's layer node in desktop pixels ({@link DesktopSpace}), which takes no clicks.
 *
 * <p>Both are kept above a fullscreen window the way the authoring overlay is ({@link OverlayToolbars}). The
 * layer is first shown one pixel wide and grows to the desktop only once the native side has made it
 * click-through; where that cannot be done it is not shown at all, since a layer that caught the bot's own
 * clicks would break the run it shows.
 */
final class RunOverlayWindows implements RunOverlay.Surface {

    static final String BAR_TITLE = "BotMaker run bar";
    static final String LAYER_TITLE = "BotMaker run layer";

    /** How often the layer's click-through and stacking are re-asserted. */
    private static final Duration LAYER_TICK = Duration.millis(250);
    /** How many ticks the layer may take to become click-through before it is given up. */
    private static final int LAYER_TRIES = 12;
    /** How often the bar reads the run's pause state. */
    private static final Duration BAR_TICK = Duration.millis(500);
    private static final double BAR_MARGIN = 16;
    private static final double TRACE_WIDTH = 420;

    private final EventBus eventBus;
    private final List<PartContext> contexts = new ArrayList<>();
    private final Stage bar;
    private final VBox trace = new VBox(2);
    private Timeline barKeeper;
    private Stage layer;
    private Timeline layerKeeper;

    private RunOverlayWindows(RunOverlay.Session session, ProjectConfig config, EventBus eventBus) {
        this.eventBus = eventBus;
        this.bar = new Stage(StageStyle.TRANSPARENT);

        List<Node> barNodes = new ArrayList<>();
        List<Node> layerNodes = new ArrayList<>();
        for (PluginHost.OwnedPart owned : PluginHost.runOverlayParts()) {
            PartContext context = new PartContext(HostServices.forProject(config, () -> bar));
            contexts.add(context);
            RunOverlayPart part = owned.part();
            part.barFactory().map(f -> build(owned, f, context)).ifPresent(barNodes::add);
            part.layerFactory().map(f -> build(owned, f, context)).ifPresent(layerNodes::add);
        }

        buildBar(session, barNodes);
        if (!layerNodes.isEmpty()) openLayer(layerNodes);
    }

    static RunOverlay.Surface open(RunOverlay.Session session, ProjectConfig config, EventBus eventBus) {
        return new RunOverlayWindows(session, config, eventBus);
    }

    /** A part's node, or null when its factory threw: one part's failure costs only that part. */
    private static Node build(PluginHost.OwnedPart owned, RunOverlayPart.Factory factory, PartContext context) {
        try {
            return factory.create(context);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Warning: " + owned.pluginId() + "'s run overlay part '" + owned.part().id()
                    + "' could not be drawn: " + e);
            return null;
        }
    }

    // --- The bar ---

    private void buildBar(RunOverlay.Session session, List<Node> barNodes) {
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
        barKeeper = new Timeline(new KeyFrame(BAR_TICK, e -> refreshPause(pause, runs, session)));
        barKeeper.setCycleCount(Animation.INDEFINITE);
        barKeeper.play();
        Button again = OverlayStyles.iconButton("↻", "Stop the bot and run it again", session::runAgain);
        show(again, !session.debugging());
        Button hide = OverlayStyles.iconButton("✕", "Hide until the next run", session::dismiss);

        HBox controls = new HBox(6, grip, stop, pause, again);
        controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        if (InputListenerFactory.isSupported()) {
            controls.getChildren().add(OverlayStyles.dimLabel(StopKeyPreference.name() + " stops it"));
        }
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        controls.getChildren().addAll(spacer, hide);
        OverlayToolbars.installDrag(controls, bar);

        VBox root = new VBox(6, controls);
        if (!barNodes.isEmpty()) root.getChildren().add(new HBox(8, barNodes.toArray(Node[]::new)));
        show(trace, false);
        root.getChildren().add(trace);
        root.setStyle(OverlayStyles.PANEL + "-fx-padding: 8;");
        // A fixed width, so the first trace lines grow the bar downwards only and it stays where it was placed.
        root.setPrefWidth(TRACE_WIDTH + 16);
        root.setMaxWidth(TRACE_WIDTH + 16);
        root.getStyleClass().add(ThemedWindows.UNTHEMED);

        Scene scene = new Scene(root, Color.TRANSPARENT);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        OverlayStyles.applyThemeClass(root);

        bar.setTitle(BAR_TITLE);
        bar.setAlwaysOnTop(true);
        bar.setScene(scene);
        bar.setOnShown(e -> place());
        bar.setOnHidden(e -> RunOverlayPreference.saveBar(bar.getX(), bar.getY()));
        bar.show();
        OverlayToolbars.promoteAboveFullscreen(bar);
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

    /** Where the user left the bar, if that is still on a screen; else the primary screen's top-right corner. */
    private void place() {
        var x = RunOverlayPreference.barX();
        var y = RunOverlayPreference.barY();
        if (x.isPresent() && y.isPresent()
                && !Screen.getScreensForRectangle(x.getAsDouble(), y.getAsDouble(), 1, 1).isEmpty()) {
            bar.setX(x.getAsDouble());
            bar.setY(y.getAsDouble());
            return;
        }
        Rectangle2D area = Screen.getPrimary().getVisualBounds();
        bar.setX(area.getMaxX() - bar.getWidth() - BAR_MARGIN);
        bar.setY(area.getMinY() + BAR_MARGIN);
    }

    @Override
    public void trace(List<TraceLine> tail) {
        List<Node> lines = new ArrayList<>();
        for (TraceLine line : tail) lines.add(traceLabel(line));
        trace.getChildren().setAll(lines);
        show(trace, !lines.isEmpty());
        bar.sizeToScene();
    }

    private static Label traceLabel(TraceLine line) {
        String text = line.count() > 1 ? line.text() + " (×" + line.count() + ")" : line.text();
        Label label = new Label(text.replace('\n', ' '));
        label.setMaxWidth(TRACE_WIDTH);
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

    // --- The layer ---

    private void openLayer(List<Node> layerNodes) {
        DesktopSpace space = DesktopSpace.of(screens());
        if (layerNodes.isEmpty() || space == null) return;
        if (!Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW)) {
            layerOff("this desktop cannot draw a transparent window");
            return;
        }
        Group content = new Group(layerNodes);
        content.getTransforms().addAll(new Scale(1 / space.scale(), 1 / space.scale()),
                new Translate(-space.originPixelX(), -space.originPixelY()));
        Pane root = new Pane(content);
        root.setMouseTransparent(true);
        root.setStyle("-fx-background-color: transparent;");
        root.getStyleClass().add(ThemedWindows.UNTHEMED);

        layer = new Stage(StageStyle.TRANSPARENT);
        layer.setTitle(LAYER_TITLE);
        layer.setAlwaysOnTop(true);
        layer.setScene(new Scene(root, Color.TRANSPARENT));
        // One pixel until it lets clicks through: a full-desktop window that caught them, even for a moment,
        // would take the bot's.
        layer.setX(space.x());
        layer.setY(space.y());
        layer.setWidth(1);
        layer.setHeight(1);
        layer.show();

        NativeController controller = NativeControllerFactory.get();
        int[] ticks = {0};
        int[] madeInARow = {0};
        boolean[] through = {false};
        layerKeeper = new Timeline(new KeyFrame(LAYER_TICK, e -> {
            ticks[0]++;
            // The first promotion remaps the window, and the window manager frames it again later, unshaped: so
            // promote alone on the first tick, and grow only after two passes that both came after the remap.
            if (ticks[0] % 3 == 1) controller.promoteOverlayAboveFullscreen(LAYER_TITLE);
            if (ticks[0] == 1) return;
            boolean made = controller.makeInputTransparent(LAYER_TITLE);
            madeInARow[0] = made ? madeInARow[0] + 1 : 0;
            if (!through[0] && madeInARow[0] >= 2) {
                through[0] = true;
                layer.setWidth(space.width());
                layer.setHeight(space.height());
            } else if (!through[0] && ticks[0] >= LAYER_TRIES) {
                layerOff("this desktop cannot make a window let clicks through");
            }
        }));
        layerKeeper.setCycleCount(Animation.INDEFINITE);
        layerKeeper.play();
    }

    /** The screens as {@link DesktopSpace} reads them, the primary first. */
    private static List<DesktopSpace.Screen> screens() {
        List<DesktopSpace.Screen> screens = new ArrayList<>();
        Function<Screen, DesktopSpace.Screen> of = s -> new DesktopSpace.Screen(s.getBounds().getMinX(),
                s.getBounds().getMinY(), s.getBounds().getWidth(), s.getBounds().getHeight(), s.getOutputScaleX());
        Screen primary = Screen.getPrimary();
        screens.add(of.apply(primary));
        for (Screen s : Screen.getScreens()) {
            if (!s.equals(primary)) screens.add(of.apply(s));
        }
        return screens;
    }

    private void layerOff(String why) {
        closeLayer();
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                "The run overlay shows no marks over the desktop: " + why + "."));
    }

    private void closeLayer() {
        if (layerKeeper != null) layerKeeper.stop();
        layerKeeper = null;
        if (layer != null) layer.hide();
        layer = null;
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    @Override
    public void close() {
        closeLayer();
        if (barKeeper != null) barKeeper.stop();
        bar.hide();
        contexts.forEach(PartContext::close);
        contexts.clear();
    }
}
