package com.botmaker.studio.ui.app.runner;

import com.botmaker.plugin.api.run.Readiness;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Run window's header A (2026-10-25): who and which release, readiness, Start/Stop, the update strip. */
class RunnerWindowHeaderFxTest extends FxHeadlessTest {

    private final List<String> asked = new ArrayList<>();
    private RunnerHeader withUpdate;
    private RunnerHeader withoutUpdate;

    @Override
    public void start(Stage stage) {
        withUpdate = header(new RunnerHeader.About("Firestone", Optional.of("LiQiyeDev"), Optional.of("1.0.0"),
                Optional.of("1.1.0")));
        withoutUpdate = header(new RunnerHeader.About("PushTest", Optional.empty(), Optional.empty(),
                Optional.empty()));
        VBox both = new VBox(withUpdate.node(), withoutUpdate.node());
        stage.setScene(new Scene(new StackPane(both), 1000, 400));
        stage.show();
    }

    private RunnerHeader header(RunnerHeader.About about) {
        return new RunnerHeader(about, "Pause", List.of(), new RunnerHeader.Actions() {
            @Override
            public void home() {
                asked.add("home");
            }

            @Override
            public void start() {
                asked.add("start " + about.name());
            }

            @Override
            public void stop() {
                asked.add("stop");
            }

            @Override
            public void update() {
                asked.add("update");
            }

            @Override
            public void whatChanged() {
                asked.add("what changed");
            }

            @Override
            public void editInStudio() {
                asked.add("edit");
            }
        });
    }

    private boolean shown(String text) {
        return lookup(".label").queryAll().stream()
                .anyMatch(n -> n instanceof Label l && text.equals(l.getText()) && n.isVisible()
                        && (n.getParent() == null || n.getParent().isVisible()));
    }

    @Test
    void theUpdateLineIsShownOnlyWhenANewerReleaseIsListed() {
        assertTrue(shown("1.1.0 is available —"));
        assertTrue(shown("by LiQiyeDev · 1.0.0"));
        assertTrue(shown("mine"));
        assertEquals(1, lookup(".runner-update").queryAll().stream().filter(n -> n.isVisible()).count());
        clickOn("Later");
        assertEquals(0, lookup(".runner-update").queryAll().stream().filter(n -> n.isVisible()).count());
    }

    @Test
    void theStopKeyIsNamedAndStartStopSwapWhileRunning() {
        Button stop = lookup("⏹ Stop (Pause)").queryButton();
        Button start = lookup("▶ Start").queryButton();
        assertTrue(stop.isDisabled());
        clickOn(start);
        assertEquals(List.of("start Firestone"), asked);

        interact(() -> withUpdate.running(true));
        assertTrue(start.isDisabled());
        assertFalse(stop.isDisabled());
        assertTrue(shown("● Running — Pause stops it"));
    }

    @Test
    void readinessIsTheStatusLine() {
        assertTrue(shown("● Ready"), "nothing said is ready");
        interact(() -> withUpdate.readiness(Optional.of(Readiness.blocked("signed out of Steam"))));
        assertTrue(shown("● Not ready — signed out of Steam"));
    }

    @Test
    void anUpdateThatNeedsStudioOffersIt() {
        clickOn("Update");
        assertEquals(List.of("update"), asked);
        interact(withUpdate::updating);
        assertEquals(1, lookup(".button").queryAll().stream()
                .filter(n -> n instanceof Button b && "▶ Start".equals(b.getText()) && b.isDisabled()).count(),
                "no run starts while the files are being updated");
        interact(() -> withUpdate.updateSaid("This update needs Studio to finish.", true));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(shown("This update needs Studio to finish."));
        clickOn("Edit in Studio");
        assertEquals(List.of("update", "edit"), asked);
    }
}
