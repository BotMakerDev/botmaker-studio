// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.fx;

import com.botmaker.core.events.ApplicationEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.ui.app.OverflowBar;
import com.botmaker.studio.ui.app.RunChoice;
import com.botmaker.studio.ui.app.ToolbarManager;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the editor toolbar's UI contracts ({@link ToolbarManager}): the Run split button publishes its choice's
 * request and keeps the last choice in the project, Run/Stop follow the run state, and Step/Continue exist only
 * while debugging. These are the UI-event-wiring assertions the AST-level tests can't make.
 */
class ToolbarInteractionTest extends FxHeadlessTest {

    private EventBus bus;
    private final List<ApplicationEvent> published = new ArrayList<>();
    private Path project;
    private ProjectSettingsService settings;
    private SplitMenuButton runButton;
    private Button stopButton;
    private OverflowBar exec;

    @Override
    public void start(Stage stage) throws Exception {
        bus = new EventBus(false);
        // A subscriber on the ApplicationEvent supertype receives every event the toolbar emits.
        bus.subscribe(ApplicationEvent.class, published::add);

        project = Files.createTempDirectory("toolbar-run");
        ProjectState state = new ProjectState();
        state.setSettings(StudioProjectSettings.empty());
        settings = new ProjectSettingsService(ProjectConfig.forDirectory(project), state, bus);
        ToolbarManager toolbar = new ToolbarManager(bus, settings);
        HBox edit = toolbar.createEditGroup();
        exec = toolbar.createExecutionGroup();

        runButton = (SplitMenuButton) exec.lookup(".btn-run");
        stopButton = (Button) exec.lookup(".btn-stop");

        stage.setScene(new Scene(new VBox(edit, exec), 600, 80));
        stage.show();
    }

    @Test
    void runButtonPublishesExecutionRequest() {
        assertNotNull(runButton, "the Run button should be locatable by its .btn-run style class");
        assertEquals(RunChoice.RUN.displayName(), runButton.getText(), "a project that never chose runs");
        interact(runButton::fire);
        assertTrue(published.stream().anyMatch(e -> e instanceof CoreApplicationEvents.ExecutionRequestedEvent),
                "clicking Run should publish an ExecutionRequestedEvent");
    }

    @Test
    void theMenuHoldsTheFourChoicesAndCompileRequestsACompile() {
        // Then, below a separator (its text null), the Runs window's two (run-claims phase 14).
        assertEquals(Arrays.asList("▶ Run", "🐞 Debug", "👁 Follow", "⚙ Compile", null, "Run several…",
                        "Show all runs on this PC"),
                runButton.getItems().stream().map(MenuItem::getText).toList());
        interact(() -> item(RunChoice.COMPILE).fire());
        assertTrue(published.stream().anyMatch(e -> e instanceof CoreApplicationEvents.CompilationRequestedEvent),
                "choosing Compile should publish a CompilationRequestedEvent");
    }

    @Test
    void theLastChoiceIsTheFaceAndSurvivesAReopen() {
        interact(() -> item(RunChoice.DEBUG).fire());
        assertEquals(RunChoice.DEBUG.displayName(), runButton.getText());
        published.clear();
        interact(runButton::fire);
        assertTrue(published.stream().anyMatch(e -> e instanceof CoreApplicationEvents.DebugStartRequestedEvent),
                "the face repeats the last choice");

        // A reopen: the settings read back from disk, and a new toolbar built over them.
        ProjectState reopened = new ProjectState();
        reopened.setSettings(StudioProjectSettings.read(ProjectConfig.forDirectory(project).studioRoot()));
        ToolbarManager again = new ToolbarManager(bus,
                new ProjectSettingsService(ProjectConfig.forDirectory(project), reopened, bus));
        SplitMenuButton face = (SplitMenuButton) again.createExecutionGroup().lookup(".btn-run");
        assertEquals(RunChoice.DEBUG.displayName(), face.getText());
    }

    @Test
    void stopIsDisabledUntilRunningThenReEnablesRunOnStop() {
        assertFalse(runButton.isDisable(), "Run is enabled while idle");
        assertTrue(stopButton.isDisable(), "Stop is disabled while idle");

        publishAndFlush(new CoreApplicationEvents.ProgramStartedEvent());
        assertFalse(stopButton.isDisable(), "Stop enables once the program is running");
        assertTrue(runButton.isDisable(), "Run disables while running");

        publishAndFlush(new CoreApplicationEvents.ProgramStoppedEvent());
        assertFalse(runButton.isDisable(), "Run re-enables once the program stops");
        assertTrue(stopButton.isDisable(), "Stop disables again once idle");
    }

    @Test
    void stepAndContinueShowOnlyWhileDebugging() {
        assertEquals(List.of(), stepAndContinue(), "neither shows outside a debug session");

        publishAndFlush(new CoreApplicationEvents.DebugSessionStartedEvent());
        assertEquals(2, stepAndContinue().size(), "a debug session shows both");
        assertTrue(stepAndContinue().stream().allMatch(Node::isDisable), "running, not paused: neither can be pressed");

        publishAndFlush(new CoreApplicationEvents.DebugSessionPausedEvent(1, null));
        assertTrue(stepAndContinue().stream().noneMatch(Node::isDisable), "paused: both can be pressed");

        publishAndFlush(new CoreApplicationEvents.DebugSessionFinishedEvent());
        assertEquals(List.of(), stepAndContinue(), "the session's end hides them again");
    }

    // --- helpers ---

    private MenuItem item(RunChoice choice) {
        return runButton.getItems().stream().filter(i -> choice.displayName().equals(i.getText()))
                .findFirst().orElseThrow();
    }

    /** Step and Continue as laid out now: managed and shown. */
    private List<Node> stepAndContinue() {
        return exec.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof Button b && (b.getText().contains("Step") || b.getText().contains("Cont")))
                .filter(n -> n.isManaged() && n.isVisible())
                .toList();
    }

    /** The toolbar subscribes to state events on the FX thread; publish then flush that queue before asserting. */
    private void publishAndFlush(ApplicationEvent event) {
        interact(() -> bus.publish(event));
        WaitForAsyncUtils.waitForFxEvents();
        interact(exec::layout);
    }
}
