package com.botmaker.studio.ui.app;

import com.botmaker.studio.assist.AssistTurn;
import com.botmaker.studio.assist.AssistWorkspace;
import com.botmaker.studio.assist.AssistantService;
import com.botmaker.studio.assist.AssistantSettings;
import com.botmaker.studio.assist.Provider;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.Arrays;
import java.util.Optional;

/**
 * The Assistant bottom tab: a model the user chose, asked in plain words to change the open file's blocks.
 *
 * <p>Everything the model does goes through {@link AssistTurn} — palette ids, values the grammar reads, a
 * compile after every edit — and lands when the reply does, as one step ↶ takes back. This class is only the
 * conversation: it takes a snapshot of the file on the FX thread, runs the model on a thread of its own, and
 * commits on the FX thread against whatever the file says by then.
 */
final class AssistantPane {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;
    private final StudioContext ctx;
    private final AssistantService service = AssistantService.withEnvironment(System::getenv);

    private final BorderPane root = new BorderPane();
    private final ComboBox<Provider> provider = new ComboBox<>();
    private final TextField model = new TextField();
    private final TextField baseUrl = new TextField();
    private final Label keyStatus = new Label();
    private final TextArea transcript = new TextArea();
    private final TextArea input = new TextArea();
    private final Button send = new Button("Send");
    private final Label status = new Label();
    private boolean working;

    AssistantPane(StudioContext ctx) {
        this.ctx = ctx;
        this.config = ctx.config();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();
        build();
        show(ProjectPreferences.loadAssistant());
    }

    Node node() {
        return root;
    }

    private void build() {
        provider.getItems().addAll(Arrays.stream(Provider.values()).filter(p -> p != Provider.UNKNOWN).toList());
        provider.setOnAction(e -> {
            Provider chosen = provider.getValue();
            if (chosen != null) show(AssistantSettings.forProvider(chosen));
        });
        model.setPromptText("model");
        model.setPrefColumnCount(16);
        baseUrl.setPromptText("server address");
        baseUrl.setPrefColumnCount(18);
        keyStatus.getStyleClass().add("dialog-hint");
        Button clear = new Button("New conversation");
        clear.getStyleClass().add("dialog-compact");
        clear.setOnAction(e -> {
            service.reset();
            transcript.clear();
        });
        HBox bar = new HBox(8, new Label("Model"), provider, model, baseUrl, keyStatus, spacer(), clear);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("diagnostics-filter-bar");

        transcript.setEditable(false);
        transcript.setWrapText(true);
        transcript.getStyleClass().add("console-area");

        input.setPromptText("Ask for a change to this file — e.g. \"print the count before the loop\". Ctrl+Enter sends.");
        input.setPrefRowCount(2);
        input.setWrapText(true);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                e.consume();
                submit();
            }
        });
        send.setDefaultButton(false);
        send.setOnAction(e -> submit());
        status.getStyleClass().add("dialog-status");
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox entry = new HBox(8, input, send);
        entry.setAlignment(Pos.CENTER_LEFT);
        BorderPane bottom = new BorderPane(entry);
        bottom.setBottom(status);
        bottom.setPadding(new Insets(6));

        root.setTop(bar);
        root.setCenter(transcript);
        root.setBottom(bottom);
    }

    private static Node spacer() {
        HBox gap = new HBox();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    /** Puts {@code settings} in the controls, and says whether its key is there. */
    private void show(AssistantSettings settings) {
        Provider kind = settings.providerKind() == Provider.UNKNOWN ? Provider.OLLAMA : settings.providerKind();
        provider.setValue(kind);
        model.setText(settings.model());
        baseUrl.setText(settings.baseUrl());
        baseUrl.setVisible(kind.takesBaseUrl());
        baseUrl.setManaged(kind.takesBaseUrl());
        if (kind.keyVariable() == null) {
            keyStatus.setText("runs locally, no key");
        } else {
            String key = System.getenv(kind.keyVariable());
            boolean present = key != null && !key.isBlank();
            keyStatus.setText(present ? kind.keyVariable() + " found"
                    : kind.requiresKey() ? "set " + kind.keyVariable() + " before starting Studio" : "no key needed");
        }
    }

    private AssistantSettings current() {
        Provider kind = provider.getValue() == null ? Provider.OLLAMA : provider.getValue();
        return new AssistantSettings(kind.id(), model.getText(), kind.takesBaseUrl() ? baseUrl.getText() : "");
    }

    private void submit() {
        String message = input.getText().strip();
        if (message.isEmpty() || working) return;
        if (state.getActiveFile() == null) {
            setStatus("Open a file first.", true);
            return;
        }
        AssistantSettings settings = current();
        ProjectPreferences.saveAssistant(settings);

        // Taken on the FX thread: the turn never reads the live state again.
        String startedFrom = state.getCurrentCode();
        AssistWorkspace workspace = AssistWorkspace.of(config, state,
                ctx.projectAnalyzer() == null ? null : ctx.projectAnalyzer().libraryIndex(), ctx.sdkSurfaceService());
        AssistTurn turn = new AssistTurn(workspace, startedFrom);

        input.clear();
        append("You", message);
        setWorking(true);
        Thread worker = new Thread(() -> {
            try {
                AssistantService.Reply reply = service.ask(settings, turn, message);
                Platform.runLater(() -> finish(reply));
            } catch (AssistantService.AssistantException e) {
                Platform.runLater(() -> fail(e.getMessage()));
            } catch (RuntimeException e) {
                Platform.runLater(() -> fail("Something went wrong: " + e));
            }
        }, "assistant-turn");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(AssistantService.Reply reply) {
        setWorking(false);
        for (String line : reply.toolLog()) transcript.appendText("    · " + line + "\n");
        append("Assistant", reply.text().isBlank() ? "(no answer)" : reply.text());
        AssistTurn turn = reply.turn();
        if (turn.acceptedEdits() == 0) {
            setStatus(reply.calledTools() ? "No change was made."
                    : "The model called no tools. It may not support tool calling; try another model.", !reply.calledTools());
            return;
        }
        Optional<com.botmaker.studio.events.CoreApplicationEvents.CodeUpdatedEvent> commit =
                turn.commit(state.getCurrentCode(), "the assistant's change");
        if (commit.isEmpty()) {
            setStatus("The file changed while the assistant was working, so its " + turn.acceptedEdits()
                    + " edit(s) were not applied.", true);
            return;
        }
        eventBus.publish(commit.get());
        setStatus("Applied " + turn.acceptedEdits() + " edit(s). Undo takes them all back.", false);
    }

    private void fail(String reason) {
        setWorking(false);
        setStatus(reason, true);
    }

    private void append(String who, String text) {
        transcript.appendText(who + ": " + text.strip() + "\n\n");
    }

    private void setWorking(boolean now) {
        working = now;
        send.setDisable(now);
        if (now) setStatus("Working…", false);
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.getStyleClass().removeAll("dialog-status--error", "dialog-status--ok");
        if (error) status.getStyleClass().add("dialog-status--error");
    }
}
