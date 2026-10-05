package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.overlay.Marks;
import com.botmaker.plugin.api.run.RunOverlayContext;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.capture.ScreenOverlay;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
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
 * The one transparent window over the whole desktop that takes no clicks: each plugin's run overlay
 * {@link RunOverlayPart#layer} in desktop pixels ({@link DesktopSpace}), and the boxes the host draws for
 * {@link Marks} ({@link HostMarks}).
 *
 * <p><b>Shared by the run and the overlay editor.</b> Each {@link #hold}s it; it opens on the first hold that has
 * something to draw — an editor always does, its marks; a run only when a part has a layer — and closes when the
 * last hold is closed. So a layer opened for editing stays through a run started from the panel, and each part's
 * {@link RunOverlayContext#mode()} says why it was opened. FX thread.
 *
 * <p>It is first shown one pixel wide and grows to the desktop only once the native side has made it
 * click-through; where that cannot be done it is not shown at all, since a layer that caught the bot's own
 * clicks would break the run it shows. It is emptied while a capture surface is up, so a pick is never made over
 * its own marks.
 */
public final class DesktopLayer {

    /** The layer window's title, which a window picker leaves out. */
    public static final String TITLE = "BotMaker run layer";

    /** How often the layer's click-through and stacking are re-asserted. */
    private static final Duration TICK = Duration.millis(250);
    /** How many ticks the layer may take to become click-through before it is given up. */
    private static final int TRIES = 12;

    /** One holder's claim on the layer. */
    public interface Lease extends AutoCloseable {
        /** A fresh handle for this holder's boxes; draws nothing while no layer is shown. */
        Marks marks();

        /** Gives the claim up; the last one closes the layer. Idempotent. */
        @Override
        void close();
    }

    private static DesktopLayer open;
    private static int holders;

    private final EventBus eventBus;
    private final List<PartContext> contexts = new ArrayList<>();
    private final HostMarks marks = new HostMarks();
    private Pane root;
    private Stage stage;
    private Timeline keeper;
    private AutoCloseable captureVisibility;

    private DesktopLayer(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Claims the layer for a run ({@code RUNNING}) or the overlay editor ({@code EDITING}), opening it when this
     * is the first claim with something to draw. FX thread.
     */
    public static Lease hold(RunOverlayContext.Mode mode, ProjectConfig config, EventBus eventBus) {
        holders++;
        if (open == null) {
            List<PluginHost.OwnedPart> parts = PluginHost.runOverlayParts().stream()
                    .filter(owned -> owned.part().layerFactory().isPresent()).toList();
            if (mode == RunOverlayContext.Mode.EDITING || !parts.isEmpty()) {
                DesktopLayer layer = new DesktopLayer(eventBus);
                open = layer;
                layer.show(mode, config, parts);
            }
        }
        return new Lease() {
            private boolean closed;

            @Override
            public Marks marks() {
                DesktopLayer layer = open;
                return closed || layer == null || layer.stage == null ? Marks.NONE : layer.marks.handle();
            }

            @Override
            public void close() {
                if (closed) return;
                closed = true;
                holders--;
                if (holders <= 0) {
                    holders = 0;
                    DesktopLayer layer = open;
                    open = null;
                    if (layer != null) layer.close();
                }
            }
        };
    }

    private void show(RunOverlayContext.Mode mode, ProjectConfig config, List<PluginHost.OwnedPart> parts) {
        List<Node> nodes = new ArrayList<>();
        for (PluginHost.OwnedPart owned : parts) {
            PartContext context = new PartContext(HostServices.forProject(config, () -> stage), mode);
            contexts.add(context);
            Node node = RunOverlayWindows.build(owned, owned.part().layerFactory().orElseThrow(), context);
            if (node != null) nodes.add(node);
        }
        nodes.add(marks.node());

        DesktopSpace space = DesktopSpace.of(screens());
        if (space == null) return;
        if (!Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW)) {
            off("this desktop cannot draw a transparent window");
            return;
        }
        Group content = new Group(nodes);
        content.getTransforms().addAll(new Scale(1 / space.scale(), 1 / space.scale()),
                new Translate(-space.originPixelX(), -space.originPixelY()));
        root = new Pane(content);
        root.setMouseTransparent(true);
        root.setStyle("-fx-background-color: transparent;");
        root.getStyleClass().add(ThemedWindows.UNTHEMED);

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setTitle(TITLE);
        stage.setAlwaysOnTop(true);
        stage.setScene(new Scene(root, Color.TRANSPARENT));
        // One pixel until it lets clicks through: a full-desktop window that caught them, even for a moment,
        // would take the bot's.
        stage.setX(space.x());
        stage.setY(space.y());
        stage.setWidth(1);
        stage.setHeight(1);
        stage.show();

        // Emptied rather than hidden: hiding unmaps the window, and shown again it would catch clicks until the
        // native side made it click-through once more.
        captureVisibility = ScreenCaptureService.addCaptureOverlayListener(new ScreenOverlay.CaptureOverlayListener() {
            @Override public void onShown() { root.setVisible(false); }
            @Override public void onHidden() { root.setVisible(true); }
        });

        NativeController controller = NativeControllerFactory.get();
        int[] ticks = {0};
        int[] madeInARow = {0};
        boolean[] through = {false};
        keeper = new Timeline(new KeyFrame(TICK, e -> {
            ticks[0]++;
            // The first promotion remaps the window, and the window manager frames it again later, unshaped: so
            // promote alone on the first tick, and grow only after two passes that both came after the remap.
            if (ticks[0] % 3 == 1) controller.promoteOverlayAboveFullscreen(TITLE);
            if (ticks[0] == 1) return;
            boolean made = controller.makeInputTransparent(TITLE);
            madeInARow[0] = made ? madeInARow[0] + 1 : 0;
            if (!through[0] && madeInARow[0] >= 2) {
                through[0] = true;
                stage.setWidth(space.width());
                stage.setHeight(space.height());
            } else if (!through[0] && ticks[0] >= TRIES) {
                off("this desktop cannot make a window let clicks through");
            }
        }));
        keeper.setCycleCount(Animation.INDEFINITE);
        keeper.play();
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

    /** Takes the window down for good, saying why on the status line; the parts stay until the last lease. */
    private void off(String why) {
        closeWindow();
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                "Nothing is drawn over the desktop: " + why + "."));
    }

    private void closeWindow() {
        if (keeper != null) keeper.stop();
        keeper = null;
        if (captureVisibility != null) {
            try {
                captureVisibility.close();
            } catch (Exception ignored) {
                // removing a listener from a list does not fail
            }
            captureVisibility = null;
        }
        if (stage != null) stage.hide();
        stage = null;
    }

    private void close() {
        closeWindow();
        contexts.forEach(PartContext::close);
        contexts.clear();
    }
}
