package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zoomed-in canvas wraps; it does not scroll sideways.
 *
 * <p>The zoom lays the program out at {@code width / zoom}, and that only helps if the rows can actually be that
 * narrow. Until 2026-09-24 they could not: a wrapping row reported its widest child's <em>one-line</em> width as
 * its minimum, so a {@code while} holding {@code count < limit && !full} was as wide as that line at any zoom, and
 * at 150% the canvas grew a horizontal scrollbar. Every operator dropdown was also as wide as its longest
 * operator. This draws the gallery's program at the maximum zoom in a 700px window and asserts that everything
 * fits the viewport.
 */
class ZoomedCanvasFitsTest extends FxHeadlessTest {

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        // 700px of canvas at the maximum zoom is 350px of layout — four levels of nesting, a list, a
        // two-line condition. It measured 876px wide before the rows wrapped and the dropdowns fitted.
        Scene scene = new Scene(root, 700, 500);
        ThemedWindows.addStylesheet(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 3; i++) {
            CountDownLatch drained = new CountDownLatch(1);
            Platform.runLater(drained::countDown);
            assertTrue(drained.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void theProgramFitsTheViewportAtTwiceTheSize() throws Exception {
        EditorFixture fixture = new EditorFixture(BlockGalleryTest.PROGRAM);
        EditorCanvas[] made = new EditorCanvas[1];
        onFx(() -> {
            made[0] = new EditorCanvas(fixture.context(), fixture.bus(), false, "Gallery", () -> {}, List::of, () -> {});
            root.getChildren().setAll(made[0].node());
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, made[0]::handleBlocksUpdate, false);
        });
        onFx(fixture::rerender);

        ScrollPane scroll = (ScrollPane) made[0].node().lookup(".code-scroll-pane");
        ZoomPane zoom = (ZoomPane) scroll.getContent();
        double[] measured = new double[3];
        onFx(() -> {
            // Unbound from the preference so the test does not rewrite the developer's saved zoom.
            zoom.zoomProperty().unbind();
            zoom.zoomProperty().set(2.0);
            root.applyCss();
            root.layout();
            Node canvas = root.lookup(".blocks-canvas");
            measured[0] = scroll.getViewportBounds().getWidth();
            measured[1] = zoom.getLayoutBounds().getWidth();
            measured[2] = canvas.minWidth(-1) * 2.0;
        });

        assertTrue(measured[1] <= measured[0] + 1,
                "the zoomed canvas is " + measured[1] + "px wide in a " + measured[0] + "px viewport: it scrolls sideways");
        assertTrue(measured[2] <= measured[0] + 1,
                "the program cannot lay out narrower than " + measured[2] + "px at 200%, wider than the "
                        + measured[0] + "px viewport — some row is refusing to wrap");
    }
}
