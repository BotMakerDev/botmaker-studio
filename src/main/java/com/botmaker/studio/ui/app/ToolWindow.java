package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * One window per project for a pane that was a bottom tab until 2026-11-13: Versions and Reports. Shown again, it
 * is the same window brought forward, read again by {@code onShow}; it closes with the project's window and is
 * titled "<what> — <bot>". Not owned, so it can sit beside Studio on another screen, as the Assistant window does.
 */
final class ToolWindow {

    private final Stage stage = new Stage();
    private final BorderPane root = new BorderPane();
    private final double width;
    private final double height;
    /** Reads the pane again each time the window is shown or brought forward. */
    private Runnable onShow = () -> { };
    private boolean built;
    private boolean disposed;
    /** The project's window, and what closes this one with it; removed on dispose (it outlives this project). */
    private Window owner;
    private EventHandler<WindowEvent> closeWithOwner;

    ToolWindow(String what, String project, double width, double height) {
        this.width = width;
        this.height = height;
        stage.setTitle(what + " — " + project);
    }

    /** What reads the pane again on each show; set after the pane, which may need this window as its owner. */
    void onShow(Runnable read) {
        this.onShow = read;
    }

    /** The pane shown; a scene rebuilt for the same project hands its new one here. */
    void content(Node node) {
        root.setCenter(node);
    }

    /** Shows the window in front, read again. */
    void show(Window owner) {
        if (disposed) return;
        if (!built) build(owner);
        onShow.run();
        stage.show();
        stage.toFront();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    /** Closes the window for good; the project's window calls this when it goes. */
    void dispose() {
        disposed = true;
        stage.close();
        // The owner is the one primary stage every project's window reuses: left on it, the handler keeps this
        // project's panes alive.
        if (owner != null) owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
        owner = null;
        root.setCenter(null);
    }

    Stage stage() {
        return stage;
    }

    private void build(Window owner) {
        built = true;
        stage.initModality(Modality.NONE);
        if (owner != null) {
            this.owner = owner;
            closeWithOwner = e -> stage.close();
            owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
            if (!Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())) {
                stage.setX(owner.getX() + 60);
                stage.setY(owner.getY() + 40);
            }
        }
        stage.setScene(ThemedWindows.scene(root, width, height));
    }
}
