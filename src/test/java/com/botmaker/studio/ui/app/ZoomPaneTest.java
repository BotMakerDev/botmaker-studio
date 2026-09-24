package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zoom is a scale <em>plus</em> a narrower layout: the content is given {@code width / zoom}, so what it wraps
 * to is the column it is actually drawn in. A bare scale would pass the first assertion and fail the rest.
 */
class ZoomPaneTest extends FxHeadlessTest {

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        stage.setScene(new Scene(root, 400, 300));
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
    }

    @Test
    void zoomingInNarrowsTheLayoutAndGrowsTheDrawing() throws Exception {
        Label text = new Label("word ".repeat(60));
        text.setWrapText(true);
        VBox content = new VBox(text);
        ZoomPane[] pane = new ZoomPane[1];
        double[] at100 = new double[3];
        double[] at200 = new double[3];

        onFx(() -> {
            pane[0] = new ZoomPane(content);
            root.getChildren().setAll(pane[0]);
            root.applyCss();
            root.layout();
            at100[0] = content.getWidth();
            at100[1] = content.getHeight();
            at100[2] = pane[0].prefHeight(400);

            pane[0].zoomProperty().set(2.0);
            root.applyCss();
            root.layout();
            at200[0] = content.getWidth();
            at200[1] = text.getHeight();
            at200[2] = pane[0].prefHeight(400);
        });

        assertEquals(400, at100[0], 0.5, "at 100% the content gets the whole width");
        assertEquals(200, at200[0], 0.5, "at 200% it is laid out at half the width, then drawn double");
        assertTrue(at200[2] > at100[2] * 2,
                "narrower means more lines, so the scaled height grows by more than the factor");
        assertEquals(pane[0].getBoundsInParent().getWidth(), 400, 0.5,
                "the drawing fills the pane exactly — nothing overflows sideways");
    }
}
