package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Versions and Reports, bottom tabs until 2026-11-13: one window each per project, read again on show. */
class ToolWindowTest extends FxHeadlessTest {

    private Stage project;
    private final AtomicInteger reads = new AtomicInteger();
    private ToolWindow window;

    @Override
    public void start(Stage stage) {
        project = stage;
        stage.show();
        window = new ToolWindow("Versions", "MyBot", 400, 300);
        window.onShow(reads::incrementAndGet);
    }

    @AfterEach
    void close() {
        fx(() -> window.dispose());
    }

    @Test
    void opensOnceAndReadsAgainOnEachShow() {
        Label pane = new Label("versions");
        fx(() -> window.content(pane));
        fx(() -> window.show(project));
        fx(() -> window.show(project));
        assertEquals(2, reads.get());
        assertEquals(1, fx(() -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && "Versions — MyBot".equals(s.getTitle())).count()));
        assertSame(window.stage().getScene(), fx(pane::getScene));
    }

    @Test
    void aRebuiltSceneHandsItsNewPane() {
        Label second = new Label("second");
        fx(() -> window.content(new Label("first")));
        fx(() -> window.content(second));
        fx(() -> window.show(project));
        assertSame(window.stage().getScene(), fx(second::getScene));
    }

    @Test
    void closesWithTheProjectsWindowAndForGoodOnDispose() {
        fx(() -> window.show(project));
        fx(() -> project.hide());
        assertFalse(fx(window::isShowing), "the project's window going takes it along");
        fx(() -> project.show());
        fx(() -> window.show(project));
        assertTrue(fx(window::isShowing));
        fx(() -> window.dispose());
        assertFalse(fx(window::isShowing));
        fx(() -> window.show(project));
        assertFalse(fx(window::isShowing), "a disposed window never shows again");
    }

    private static void fx(Runnable action) {
        fx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T fx(Callable<T> call) {
        try {
            return WaitForAsyncUtils.asyncFx(call).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
