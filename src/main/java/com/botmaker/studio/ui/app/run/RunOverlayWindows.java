package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.plugin.api.run.RunOverlayContext;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.ui.app.overlay.OverlayStyles;
import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * The run overlay's windows for one run: the <b>bar</b>, a small window the user places, with the controls,
 * the trace's last lines and each plugin's bar node ({@link RunBar}) — drawn in the overlay editor's header
 * instead while that panel is open ({@link RunBarDock}); and a hold on the <b>layer</b>, the one click-through
 * window over the desktop holding each plugin's layer node ({@link DesktopLayer}), which the overlay editor
 * may already have open.
 *
 * <p>The bar is kept above a fullscreen window the way the authoring overlay is ({@link OverlayToolbars}).
 */
final class RunOverlayWindows implements RunOverlay.Surface {

    private static final double BAR_MARGIN = 16;
    private static final double TRACE_WIDTH = 420;

    private final List<PartContext> contexts = new ArrayList<>();
    private final Stage bar;
    private final DesktopLayer.Lease layer;
    private RunBar runBar;
    /** The bar window's content: the {@link RunBar}, unless the overlay editor's panel holds it. */
    private StackPane floating;
    private AutoCloseable dockListener;
    private boolean promoted;

    private RunOverlayWindows(RunOverlay.Session session, ProjectConfig config, EventBus eventBus) {
        this.bar = new Stage(StageStyle.TRANSPARENT);

        List<Node> barNodes = new ArrayList<>();
        for (PluginHost.OwnedPart owned : PluginHost.runOverlayParts()) {
            RunOverlayPart part = owned.part();
            if (part.barFactory().isEmpty()) continue;
            PartContext context = new PartContext(HostServices.forProject(config, this::barWindow),
                    RunOverlayContext.Mode.RUNNING);
            contexts.add(context);
            Node node = build(owned, part.barFactory().get(), context);
            if (node != null) barNodes.add(node);
        }

        buildBar(session, barNodes);
        layer = DesktopLayer.hold(RunOverlayContext.Mode.RUNNING, config, eventBus);
    }

    static RunOverlay.Surface open(RunOverlay.Session session, ProjectConfig config, EventBus eventBus) {
        return new RunOverlayWindows(session, config, eventBus);
    }

    /** A part's node, or null when its factory threw: one part's failure costs only that part. */
    static Node build(PluginHost.OwnedPart owned, RunOverlayPart.Factory factory, PartContext context) {
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

        bar.setTitle(RunBarDock.BAR_TITLE);
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

    @Override
    public void close() {
        layer.close();
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
