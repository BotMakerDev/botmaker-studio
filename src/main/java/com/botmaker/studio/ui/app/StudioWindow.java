package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * The one way Studio opens a secondary window.
 *
 * <p>Twenty-three files built a {@link Stage} by hand and each answered the same five questions its own way:
 * who owns it, whether it is modal, whether it is themed, how small it may be dragged, and where it comes up.
 * The answers had drifted — some dialogs set no owner (so the window manager could put them behind the shell
 * and lose them), several passed a size to {@code new Scene(root, w, h)} and none remembered the size the user
 * dragged it to. Every one of those is a property of *being a Studio window*, not of being the Resource
 * Manager, so they live here.
 *
 * <p>The size a caller passes is a <em>default</em>, used the first time and never again: once the window has
 * been resized, {@code ProjectPreferences.loadDialogState(key)} answers instead. That is what the {@code key}
 * is for, and it is why it must stay stable across releases — rename one and users silently get the default
 * back.
 *
 * <p><b>The owner does not move.</b> A dialog opening or closing has repeatedly nudged the main window: the
 * shell records its own geometry only while focused and un-maximized ({@code BotMakerStudio.configureWindow}),
 * which stops the drift being *written*, but not the window manager reporting it in the first place. So the
 * owner's geometry is read before the show and put back at both ends — a pulse after the dialog appears and
 * again when it goes away — the maximize included, since that is the form the drift takes on a maximized shell.
 * It is a small, verifiable guard at the two moments the drift happens, rather than a theory about which of
 * X11, GTK and JavaFX moved it.
 *
 * <p>Typical use — note that the stage exists before the content, because content routinely closes it:
 * <pre>{@code
 * StudioWindow window = StudioWindow.modal("resource-manager", "Resource Manager", owner)
 *         .size(1100, 720).minSize(760, 480);
 * this.stage = window.stage();
 * ... build root, whose buttons call stage.close() ...
 * window.show(root);
 * }</pre>
 */
public final class StudioWindow {

    /**
     * True when the window manager owns this stage's rectangle — maximized <em>or</em> fullscreen.
     *
     * <p>Every geometry site in Studio branched on {@code isMaximized()} alone, so a fullscreen shell fell
     * into the plain-window branch and had a saved x/y/w/h written over it: the shrink-and-jump-to-the-
     * top-left the maintainer saw when a dialog opened over a fullscreen editor. The two states differ in how
     * the user leaves them and in nothing that matters to geometry — in both, a remembered rectangle is a
     * rectangle from another time. Public and here rather than duplicated: {@code BotMakerStudio} asks the
     * same question of the shell.
     */
    public static boolean fillsScreen(Stage stage) {
        return stage != null && (stage.isMaximized() || stage.isFullScreen());
    }

    /**
     * Shows the screen {@code built} carries on the shell's {@code stage} without moving or resizing it.
     *
     * <p><b>The shell keeps one {@link Scene} for its whole life; each screen swaps its root in
     * (2026-09-29).</b> The shell changes screen several times in a session — project selector, loading
     * screen, editor, Runner — and used to {@code setScene} each one. {@link GeometryTrace}, run under KWin,
     * showed what that did to a maximized shell: JavaFX sizes a scene set on a shown stage from the window's
     * <em>restored</em> size, so the editor's scene came up 1440×872 inside a 1600×972 frame (the border, black
     * in the dark theme). The cure then was to drop the maximize flag and set it again in the same pulse to
     * force a re-fill — and KWin, answering both requests asynchronously, could end on the un-maximize: the
     * window "resized for no reason", and the drop was saved, so the next start opened small too. A root
     * swapped into the scene already showing is laid out at the window's real size, so there is nothing to
     * re-fill and no flag to touch.
     *
     * <p>A screen's scene is only its carrier: its root moves over, and so do its stylesheets. Anything a
     * screen hangs on its <em>scene</em> would stay behind, so the screens hang it on their root.
     */
    public static void showOnShell(Stage stage, Scene built) {
        Scene shown = stage.getScene();
        if (shown == null) {
            stage.setScene(built);
            return;
        }
        Parent root = built.getRoot();
        // A node belongs to one scene: take it out of the one it was built in before it moves.
        built.setRoot(new Group());
        shown.getStylesheets().setAll(built.getStylesheets());
        shown.setRoot(root);
    }

    /** How long after the last move/resize the geometry is written. A drag is hundreds of events. */
    private static final Duration WRITE_DELAY = Duration.millis(600);
    /** Below this, a "remembered" position is one the user cannot reach — an unplugged second screen. */
    private static final double ON_SCREEN_MARGIN = 60;

    private final String key;
    private final Stage stage = new Stage();
    private final Window owner;
    private double width = 900;
    private double height = 640;
    private double minWidth;
    private double minHeight;

    private StudioWindow(String key, String title, Window owner, Modality modality) {
        this.key = key;
        this.owner = owner;
        if (owner != null) stage.initOwner(owner);
        stage.initModality(modality);
        stage.setTitle(title);
    }

    /** A window the user must deal with before returning to the one that opened it — most of Studio's. */
    public static StudioWindow modal(String key, String title, Window owner) {
        return new StudioWindow(key, title, owner, Modality.APPLICATION_MODAL);
    }

    /** A window the user can leave open while working — a palette, a log, a live view. */
    public static StudioWindow plain(String key, String title, Window owner) {
        return new StudioWindow(key, title, owner, Modality.NONE);
    }

    /** The size to open at the <em>first</em> time; afterwards the remembered size wins. */
    public StudioWindow size(double width, double height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * The smallest the window may be dragged. Set it to the point below which the content stops being usable
     * rather than to the point where it stops looking tidy — the whole reason a window has a minimum is that a
     * scroll bar is a better answer than a clipped button.
     */
    public StudioWindow minSize(double width, double height) {
        this.minWidth = width;
        this.minHeight = height;
        return this;
    }

    /** The stage, for the content to close, title or listen to. Not yet shown and not yet sized. */
    public Stage stage() {
        return stage;
    }

    /** Themes {@code content}, restores the geometry, shows the window. */
    public Stage show(Parent content) {
        prepare(content);
        stage.show();
        return stage;
    }

    /** {@link #show} for a caller that blocks until the window is closed. */
    public void showAndWait(Parent content) {
        prepare(content);
        stage.showAndWait();
    }

    private void prepare(Parent content) {
        // Unsized on purpose. A Scene built with a width and height resizes the Stage it is set on, which is
        // the wrong way round here: the stage already knows where it goes, from what the user last did.
        Scene scene = ThemedWindows.scene(content);
        GeometryTrace.install(stage, key);
        stage.setScene(scene);
        if (minWidth > 0) stage.setMinWidth(minWidth);
        if (minHeight > 0) stage.setMinHeight(minHeight);
        restoreGeometry();
        rememberGeometry();
        pinOwner();
    }

    private void restoreGeometry() {
        ProjectPreferences.WindowState saved = ProjectPreferences.loadDialogState(key);
        Rectangle2D screen = saved == null ? null : screenOf(saved);
        if (screen != null) {
            // Whole, on the screen it overlaps: a window saved on a wide monitor would otherwise open with its
            // right half — and the buttons there — past the edge of a smaller one.
            Rectangle2D fitted = fitInto(new Rectangle2D(saved.getX(), saved.getY(), saved.getWidth(),
                    saved.getHeight()), screen);
            stage.setX(fitted.getMinX());
            stage.setY(fitted.getMinY());
            stage.setWidth(fitted.getWidth());
            stage.setHeight(fitted.getHeight());
            if (saved.isMaximized()) stage.setMaximized(true);
            return;
        }
        // No usable memory: the caller's size, centred on the owner. Leaving the position to JavaFX centres on
        // the *screen*, which on a second monitor puts the dialog somewhere the user isn't looking.
        stage.setWidth(width);
        stage.setHeight(height);
        centreOnOwner();
    }

    private void centreOnOwner() {
        if (owner == null || Double.isNaN(owner.getX()) || owner.getWidth() <= 0) return;
        stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
        stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
    }

    /** The visual bounds of the first attached screen enough of {@code state} lands on, or null for none. */
    private static Rectangle2D screenOf(ProjectPreferences.WindowState state) {
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D b = screen.getVisualBounds();
            boolean overlaps = state.getX() + state.getWidth() - ON_SCREEN_MARGIN > b.getMinX()
                    && state.getX() + ON_SCREEN_MARGIN < b.getMaxX()
                    && state.getY() + state.getHeight() - ON_SCREEN_MARGIN > b.getMinY()
                    && state.getY() + ON_SCREEN_MARGIN < b.getMaxY();
            if (overlaps) return b;
        }
        return null;
    }

    /** {@code window} moved, and shrunk when it must be, to lie wholly inside {@code screen}. Pure. */
    static Rectangle2D fitInto(Rectangle2D window, Rectangle2D screen) {
        double width = Math.min(window.getWidth(), screen.getWidth());
        double height = Math.min(window.getHeight(), screen.getHeight());
        double x = Math.max(screen.getMinX(), Math.min(window.getMinX(), screen.getMaxX() - width));
        double y = Math.max(screen.getMinY(), Math.min(window.getMinY(), screen.getMaxY() - height));
        return new Rectangle2D(x, y, width, height);
    }

    private void rememberGeometry() {
        ProjectPreferences.WindowState state = new ProjectPreferences.WindowState(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
        PauseTransition write = new PauseTransition(WRITE_DELAY);
        write.setOnFinished(e -> ProjectPreferences.saveDialogState(key, state));

        ChangeListener<Number> geom = (obs, was, is) -> {
            if (!stage.isShowing() || fillsScreen(stage)) return;
            state.setX(stage.getX());
            state.setY(stage.getY());
            state.setWidth(stage.getWidth());
            state.setHeight(stage.getHeight());
            write.playFromStart();
        };
        stage.xProperty().addListener(geom);
        stage.yProperty().addListener(geom);
        stage.widthProperty().addListener(geom);
        stage.heightProperty().addListener(geom);
        stage.maximizedProperty().addListener((obs, was, is) -> state.setMaximized(is));

        // On hide rather than only on the timer: closing a dialog a second after resizing it is exactly the
        // gesture the debounce would otherwise swallow.
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> {
            write.stop();
            ProjectPreferences.saveDialogState(key, state);
        });
    }

    /**
     * See the class note: the window that opened this one is where it was, afterwards.
     *
     * <p>Two things this used to miss, and both are what the user actually reported. It restored only on
     * <b>hide</b> — but the shift is visible the moment the dialog <em>opens</em>, and a shell that jumps at
     * open and is put back at close has still moved for as long as the dialog is up. And it returned early on
     * a <b>maximized</b> owner, on the reasoning that the window manager owns that geometry: true, and beside
     * the point, because what happens there is the maximize being dropped, which no amount of x/y restoring
     * would have addressed. Asking for the maximize again is the restore for that case.
     */
    private void pinOwner() {
        if (!(owner instanceof Stage ownerStage)) return;
        boolean wasFullScreen = ownerStage.isFullScreen();
        boolean wasMaximized = ownerStage.isMaximized();
        boolean wasFilling = wasFullScreen || wasMaximized;
        double x = ownerStage.getX();
        double y = ownerStage.getY();
        double w = ownerStage.getWidth();
        double h = ownerStage.getHeight();
        if (!wasFilling && (Double.isNaN(x) || w <= 0)) return;

        Runnable restore = () -> {
            if (!ownerStage.isShowing()) return;
            if (wasFilling) {
                // Ask for the state it was actually in: a fullscreen shell put back with setMaximized would
                // come back as a maximized one, which is a different window from the user's point of view.
                if (wasFullScreen && !ownerStage.isFullScreen()) ownerStage.setFullScreen(true);
                else if (wasMaximized && !ownerStage.isMaximized()) ownerStage.setMaximized(true);
                return;
            }
            if (fillsScreen(ownerStage)) return;   // the user filled the screen themselves; that is not drift
            if (ownerStage.getWidth() != w) ownerStage.setWidth(w);
            if (ownerStage.getHeight() != h) ownerStage.setHeight(h);
            if (ownerStage.getX() != x) ownerStage.setX(x);
            if (ownerStage.getY() != y) ownerStage.setY(y);
        };

        // One pulse after the show: the move arrives with the window manager's response to the new window, not
        // synchronously with show(), so correcting it in the same frame corrects nothing.
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> Platform.runLater(restore));
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> restore.run());
    }
}
