package com.botmaker.studio.ui.app.loading;

import com.botmaker.studio.services.OpenStep;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.ui.app.loading.LoadingPage.State;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loading page (2026-10-25, spec § 1b, B): steps ticked as they end, the current one with a bar, a failure said
 * in place with Try again, Back to My bots and Details.
 */
class LoadingPageTest extends FxHeadlessTest {

    private LoadingPage page;
    private final List<String> pressed = new CopyOnWriteArrayList<>();

    @Override
    public void start(Stage stage) {
        page = new LoadingPage(new LoadingPage.Bot("Firestone", "by LiQiyeDev · 1.0.2",
                Optional.of("Idle game farming"), Optional.empty()), OpenStep.after(OpenStep.DOWNLOADING));
        stage.setScene(new Scene(page.root(), 900, 600));
        stage.show();
    }

    private void onFx(Runnable work) {
        Platform.runLater(work);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private List<String> labels() {
        List<String> texts = new ArrayList<>();
        for (Node n : page.root().lookupAll(".label")) {
            if (n instanceof Label l && l.isVisible() && l.getText() != null) texts.add(l.getText());
        }
        return texts;
    }

    @Test
    void theBotAndEveryStepShowWaitingFirst() {
        List<String> texts = labels();
        assertTrue(texts.containsAll(List.of("Firestone", "by LiQiyeDev · 1.0.2", "Idle game farming")));
        assertTrue(texts.containsAll(List.of("Downloading", "Dependencies", "Libraries", "Project", "Files",
                "Editor")));
        for (OpenStep step : OpenStep.after(OpenStep.DOWNLOADING)) assertEquals(State.WAITING, page.state(step));
    }

    @Test
    void eachStepIsDoneWhenTheNextStartsAndAPassedOneToo() {
        onFx(() -> page.step(OpenStep.DOWNLOADING));
        assertEquals(State.CURRENT, page.state(OpenStep.DOWNLOADING));
        onFx(() -> page.progress(0.42, "Downloading 4.2 MB of 10.0 MB"));
        assertTrue(labels().contains("Downloading 4.2 MB of 10.0 MB — 42%"));
        ProgressBar bar = (ProgressBar) page.root().lookupAll(".progress-bar").stream().filter(Node::isVisible)
                .findFirst().orElseThrow();
        assertEquals(0.42, bar.getProgress(), 1e-9);

        // Libraries before Dependencies was reported: both earlier steps count as done.
        onFx(() -> page.step(OpenStep.LIBRARIES));
        assertEquals(State.DONE, page.state(OpenStep.DOWNLOADING));
        assertEquals(State.DONE, page.state(OpenStep.DEPENDENCIES));
        assertEquals(State.CURRENT, page.state(OpenStep.LIBRARIES));
        assertEquals(State.WAITING, page.state(OpenStep.PROJECT));
        assertTrue(labels().stream().anyMatch(t -> t.endsWith(" s")), "a done step says how long it took");
    }

    @Test
    void aReporterOnAnotherThreadReachesThePage() throws Exception {
        ProgressReporter progress = page.reporter();
        Thread open = new Thread(() -> {
            progress.step(OpenStep.DEPENDENCIES);
            for (int i = 0; i <= 100; i++) progress.report(i / 100.0, "Downloading sdk.jar");
        });
        open.start();
        open.join();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(State.CURRENT, page.state(OpenStep.DEPENDENCIES));
        assertTrue(labels().contains("Downloading sdk.jar — 100%"), "the last report wins: " + labels());
    }

    @Test
    void aFailureSaysWhyInPlaceWithItsWaysOut() {
        onFx(() -> {
            page.step(OpenStep.DEPENDENCIES);
            page.fail("Could not open this bot: no network", new IllegalStateException("no network"),
                    () -> pressed.add("retry"), () -> pressed.add("back"));
        });
        assertEquals(State.FAILED, page.state(OpenStep.DEPENDENCIES));
        assertTrue(labels().contains("Could not open this bot: no network"));
        TextArea trace = (TextArea) page.root().lookup(".loading-trace");
        assertFalse(trace.isVisible(), "the stack trace waits for Details");
        clickOn(button("Details"));
        assertTrue(trace.isVisible());
        assertTrue(trace.getText().contains("IllegalStateException"));
        clickOn(button("Try again"));
        clickOn(button("Back to My bots"));
        assertEquals(List.of("retry", "back"), pressed);
    }

    @Test
    void aReportNeverLandsOnTheStepBeforeIt() {
        ProgressReporter progress = page.reporter();
        // All three queued before the FX thread runs any: the libraries' line must not reach Dependencies.
        onFx(() -> {
            progress.step(OpenStep.DEPENDENCIES);
            progress.report(0.5, "Downloading sdk.jar");
            progress.step(OpenStep.LIBRARIES);
            progress.report(-1, "Indexing 40 jars");
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(State.CURRENT, page.state(OpenStep.LIBRARIES));
        assertTrue(labels().contains("Indexing 40 jars"), "the line is the current step's: " + labels());
    }

    @Test
    void aFailureAnotherTryCannotMendOffersNoTryAgain() {
        onFx(() -> page.fail("This project is from a newer Studio.", null, null, () -> pressed.add("back")));
        assertTrue(page.root().lookupAll(".button").stream().noneMatch(n -> "Try again".equals(((Button) n).getText())));
        clickOn(button("Back to My bots"));
        assertEquals(List.of("back"), pressed);
    }

    @Test
    void stepTimesReadShort() {
        assertEquals("0.4 s", LoadingPage.took(400_000_000L));
        assertEquals("12 s", LoadingPage.took(12_000_000_000L));
        assertEquals("1 min 5 s", LoadingPage.took(65_000_000_000L));
    }

    private Button button(String text) {
        return page.root().lookupAll(".button").stream().map(Button.class::cast)
                .filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }
}
