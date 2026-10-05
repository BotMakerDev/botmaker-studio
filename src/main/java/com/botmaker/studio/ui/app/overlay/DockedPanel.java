package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Rectangle;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The overlay editor's window: borderless, above the game (fullscreen included), docked beside the watched
 * screen ({@link DockPlacement}) and following it when it moves. Dragging it by its handle un-docks it until
 * {@link #dock()}; either side edge resizes its width.
 *
 * <p>Not owned by Studio's window, so Studio can be minimised while the panel stays. FX thread.
 */
final class DockedPanel {

    static final double DEFAULT_WIDTH = 380;
    private static final double MIN_WIDTH = 300;
    private static final double MAX_WIDTH = 720;
    /** How often the watched screen's bounds are read, to follow it. */
    private static final Duration FOLLOW = Duration.seconds(1);

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final Supplier<Rectangle> watchedBounds;
    private final Consumer<Rectangle> onBounds;
    private final Consumer<Boolean> onDocked;
    private final Timeline follow;
    private Rectangle lastBounds;
    private boolean docked = true;
    private boolean reading;

    /**
     * @param content       the panel's content
     * @param handle        what it is dragged by
     * @param watchedBounds the watched screen's bounds now, {@code null} when gone; read off the FX thread
     * @param onBounds      told the watched screen's bounds each time they are read, on the FX thread
     * @param onDocked      told whether the panel is docked each time that changes
     * @param promote       whether the panel may re-raise itself now (not while a popover of its own is open)
     */
    DockedPanel(Node content, Node handle, double width, Supplier<Rectangle> watchedBounds,
                Consumer<Rectangle> onBounds, Consumer<Boolean> onDocked, BooleanSupplier promote) {
        this.watchedBounds = watchedBounds;
        this.onBounds = onBounds;
        this.onDocked = onDocked;
        BorderPane root = new BorderPane(content);
        root.setLeft(grip(true));
        root.setRight(grip(false));
        // -fx-background is what modena derives text colours from: a plugin's plain Label reads light on the
        // dark panel without knowing it is dark. Opted out of ThemedWindows.install(): a transparent overlay's
        // styling is its own, though it takes the stylesheet and the theme class.
        root.setStyle("-fx-background-color: transparent; -fx-background: rgb(20,24,33);");
        root.getStyleClass().add(ThemedWindows.UNTHEMED);
        Scene scene = new Scene(root, Color.TRANSPARENT);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        OverlayStyles.applyThemeClass(root);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.setWidth(Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)));
        installDrag(handle);
        OverlayToolbars.promoteAboveFullscreen(stage, promote);
        follow = new Timeline(new KeyFrame(FOLLOW, e -> readBounds()));
        follow.setCycleCount(Animation.INDEFINITE);
    }

    Stage stage() {
        return stage;
    }

    Scene scene() {
        return stage.getScene();
    }

    double width() {
        return stage.getWidth();
    }

    /** Shows the panel docked beside {@code bounds}, and starts following the watched screen. */
    void show(Rectangle bounds) {
        lastBounds = bounds;
        place();
        stage.show();
        follow.play();
    }

    /** Docks beside the watched screen again after a drag. */
    void dock() {
        setDocked(true);
        place();
    }

    /** Docks beside {@code bounds}: the watched screen changed. */
    void dockBeside(Rectangle bounds) {
        lastBounds = bounds;
        dock();
    }

    void close() {
        follow.stop();
        stage.close();
    }

    private void place() {
        if (lastBounds == null || !docked) return;
        List<Rectangle2D> screens = new java.util.ArrayList<>();
        screens.add(Screen.getPrimary().getVisualBounds());
        for (Screen s : Screen.getScreens()) {
            if (!s.equals(Screen.getPrimary())) screens.add(s.getVisualBounds());
        }
        Rectangle2D at = DockPlacement.dock(lastBounds, screens, stage.getWidth());
        stage.setX(at.getMinX());
        stage.setY(at.getMinY());
        stage.setHeight(at.getHeight());
    }

    /** Reads the watched screen's bounds off the FX thread, and re-docks when they moved. */
    private void readBounds() {
        if (reading) return;
        reading = true;
        Thread.ofVirtual().start(() -> {
            Rectangle now;
            try {
                now = watchedBounds.get();
            } catch (RuntimeException e) {
                now = null;
            }
            Rectangle read = now;
            Platform.runLater(() -> {
                reading = false;
                if (!stage.isShowing()) return;
                onBounds.accept(read);
                if (read != null && !Objects.equals(read, lastBounds)) {
                    lastBounds = read;
                    place();
                }
            });
        });
    }

    private void setDocked(boolean now) {
        if (docked == now) return;
        docked = now;
        onDocked.accept(now);
    }

    private void installDrag(Node handle) {
        double[] offset = new double[2];
        handle.setOnMousePressed(e -> {
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(e -> {
            setDocked(false);
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
    }

    /** A thin edge that resizes the panel's width; the left one keeps the right edge still. */
    private Region grip(boolean left) {
        Region grip = new Region();
        grip.setPrefWidth(5);
        grip.setMinWidth(5);
        grip.setCursor(Cursor.H_RESIZE);
        double[] start = new double[3];
        grip.setOnMousePressed(e -> {
            start[0] = e.getScreenX();
            start[1] = stage.getWidth();
            start[2] = stage.getX();
        });
        grip.setOnMouseDragged(e -> {
            double dx = e.getScreenX() - start[0];
            double width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, start[1] + (left ? -dx : dx)));
            if (left) stage.setX(start[2] + start[1] - width);
            stage.setWidth(width);
        });
        grip.setOnMouseReleased(e -> place());
        return grip;
    }
}
