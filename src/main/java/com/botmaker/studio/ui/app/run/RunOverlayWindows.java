package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
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
 * the trace's last lines and each plugin's bar node ({@link RunBar}) — drawn in the overlay editor's header
 * instead while that panel is open ({@link RunBarDock}); and the <b>layer</b>, a transparent window over the whole
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
    private static final double BAR_MARGIN = 16;
    private static final double TRACE_WIDTH = 420;

    private final EventBus eventBus;
    private final List<PartContext> contexts = new ArrayList<>();
    private final Stage bar;
    private RunBar runBar;
    /** The bar window's content: the {@link RunBar}, unless the overlay editor's panel holds it. */
    private StackPane floating;
    private AutoCloseable dockListener;
    private boolean promoted;
    private Stage layer;
    private Timeline layerKeeper;

    private RunOverlayWindows(RunOverlay.Session session, ProjectConfig config, EventBus eventBus) {
        this.eventBus = eventBus;
        this.bar = new Stage(StageStyle.TRANSPARENT);

        List<Node> barNodes = new ArrayList<>();
        List<Node> layerNodes = new ArrayList<>();
        for (PluginHost.OwnedPart owned : PluginHost.runOverlayParts()) {
            PartContext context = new PartContext(HostServices.forProject(config, this::barWindow));
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
        runBar = new RunBar(session, barNodes);

        // The bar's own window holds it only while no overlay editor panel has taken it (RunBarDock).
        floating = new StackPane();
        // -fx-background is what modena derives a label's text colour from: a plugin's plain Label reads light
        // on the dark panel without knowing the bar is dark.
        floating.setStyle(OverlayStyles.PANEL + "-fx-background: rgb(20,24,33); -fx-padding: 8;");
        // A fixed width, so the first trace lines grow the bar downwards only and it stays where it was placed.
        floating.setPrefWidth(TRACE_WIDTH + 16);
        floating.setMaxWidth(TRACE_WIDTH + 16);
        floating.getStyleClass().add(ThemedWindows.UNTHEMED);

        Scene scene = new Scene(floating, Color.TRANSPARENT);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        OverlayStyles.applyThemeClass(floating);

        bar.setTitle(BAR_TITLE);
        bar.setAlwaysOnTop(true);
        bar.setScene(scene);
        bar.setOnShown(e -> place());
        bar.setOnHidden(e -> RunOverlayPreference.saveBar(bar.getX(), bar.getY()));
        dockListener = RunBarDock.listen(this::placeBar);
        placeBar();
    }

    /** The window the bar is drawn in now — its own, or the overlay editor's — for a part's dialogs to sit on. */
    private javafx.stage.Window barWindow() {
        if (bar.isShowing()) return bar;
        return RunBarDock.slot().map(Node::getScene).map(Scene::getWindow).orElse(bar);
    }

    /** Puts the bar in the panel's slot when one is offered, else in its own window. */
    private void placeBar() {
        var slot = RunBarDock.slot();
        if (slot.isPresent()) {
            floating.getChildren().clear();
            // Docked, its controls row drags nothing: the window it would drag is hidden.
            runBar.handle().setOnMousePressed(null);
            runBar.handle().setOnMouseDragged(null);
            slot.get().getChildren().setAll(runBar.node());
            if (bar.isShowing()) bar.hide();
            return;
        }
        OverlayToolbars.installDrag(runBar.handle(), bar);
        floating.getChildren().setAll(runBar.node());
        if (!bar.isShowing()) {
            bar.show();
            if (!promoted) OverlayToolbars.promoteAboveFullscreen(bar);
            promoted = true;
        }
        bar.sizeToScene();
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
        runBar.trace(tail);
        if (bar.isShowing()) bar.sizeToScene();
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

    @Override
    public void close() {
        closeLayer();
        if (dockListener != null) {
            try {
                dockListener.close();
            } catch (Exception ignored) {
                // removing a listener from a list does not fail
            }
        }
        runBar.close();
        // Out of the panel's slot too, or a finished run's bar stays in its header.
        RunBarDock.slot().ifPresent(slot -> slot.getChildren().remove(runBar.node()));
        bar.hide();
        contexts.forEach(PartContext::close);
        contexts.clear();
    }
}
