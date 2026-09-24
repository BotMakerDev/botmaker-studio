package com.botmaker.studio.ui.app;

import com.botmaker.studio.assist.AssistTurn;
import com.botmaker.studio.assist.AssistWorkspace;
import com.botmaker.studio.assist.AssistantService;
import com.botmaker.studio.assist.AssistantSettings;
import com.botmaker.studio.assist.McpConfig;
import com.botmaker.studio.assist.McpEndpoint;
import com.botmaker.studio.assist.ModelFactory;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
    /** Editable: the provider's list fills it, and an id the list lacks can still be typed. */
    private final ComboBox<String> model = new ComboBox<>();
    private final Button refreshModels = new Button("↻");
    private final TextField baseUrl = new TextField();
    private final Label modelStatus = new Label();
    /** A listed model's nicer name, by id, for the dropdown's cells. */
    private final Map<String, String> modelLabels = new HashMap<>();
    /** Bumped per listing request, so a slow answer never lands over a newer one. */
    private int listing;
    private String listedUrl = "";
    private boolean showing;
    private final TextArea transcript = new TextArea();
    private final TextArea input = new TextArea();
    private final Button send = new Button("Send");
    private final Label status = new Label();
    private final CheckBox serve = new CheckBox("Serve to MCP clients");
    private final Label mcpStatus = new Label();
    private final Button copySetup = new Button("Copy Claude Code setup");
    private McpConfig mcpConfig = McpConfig.load();
    private McpEndpoint endpoint;
    private boolean disposed;
    private boolean working;

    AssistantPane(StudioContext ctx) {
        this.ctx = ctx;
        this.config = ctx.config();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();
        build();
        show(ProjectPreferences.loadAssistant());
        serve.setSelected(mcpConfig.enabled());
        setServing(mcpConfig.enabled());
    }

    Node node() {
        return root;
    }

    /** Stops the MCP endpoint, if it is running. The window calls this when it goes. */
    void dispose() {
        disposed = true;
        stopEndpoint();
    }

    /**
     * Starts or stops the MCP endpoint and remembers the choice. Starting is off the FX thread — a Jetty start
     * is quick, but a taken port is a bind timeout on some systems — and a failure unticks the box.
     */
    private void setServing(boolean on) {
        mcpConfig = mcpConfig.withEnabled(on);
        mcpConfig.save();
        copySetup.setDisable(!on);
        if (!on) {
            stopEndpoint();
            mcpStatus.setText("Off. Turn on to let an MCP client (Claude Code, Cursor, …) edit the open file.");
            return;
        }
        if (endpoint != null) return;
        mcpStatus.setText("Starting…");
        McpConfig starting = mcpConfig;
        Thread starter = new Thread(() -> {
            try {
                McpEndpoint started = McpEndpoint.start(starting.port(), starting.token(), new LiveEditorFile(ctx));
                Platform.runLater(() -> {
                    // A start that finishes after the box was unticked or the window closed must let go of
                    // the port, or the next window's endpoint cannot bind it.
                    if (disposed || !serve.isSelected()) {
                        started.close();
                        return;
                    }
                    endpoint = started;
                    mcpStatus.setText("Serving " + started.url() + " (token required)");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    serve.setSelected(false);
                    mcpConfig = mcpConfig.withEnabled(false);
                    copySetup.setDisable(true);
                    mcpStatus.setText("Could not listen on port " + starting.port() + ": " + e.getMessage());
                });
            }
        }, "mcp-start");
        starter.setDaemon(true);
        starter.start();
    }

    private void stopEndpoint() {
        if (endpoint == null) return;
        endpoint.close();
        endpoint = null;
    }

    private void build() {
        provider.getItems().addAll(Arrays.stream(Provider.values()).filter(p -> p != Provider.UNKNOWN).toList());
        provider.setOnAction(e -> {
            Provider chosen = provider.getValue();
            if (chosen != null && !showing) show(AssistantSettings.forProvider(chosen));
        });
        model.setEditable(true);
        model.setPromptText("model");
        model.setPrefWidth(220);
        model.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                String label = empty || id == null ? null : modelLabels.getOrDefault(id, id);
                setText(label == null ? null : label.equals(id) ? id : label + "  (" + id + ")");
            }
        });
        model.getEditor().textProperty().addListener((obs, was, now) -> updateSend());
        refreshModels.getStyleClass().add("dialog-compact");
        refreshModels.setTooltip(new Tooltip("List the models this provider offers again"));
        refreshModels.setOnAction(e -> listModels());
        baseUrl.setPromptText("server address");
        baseUrl.setPrefColumnCount(18);
        // A server address is committed with Enter or by leaving the field, not per keystroke: each listing
        // is a request, and a half-typed URL is a request to nowhere.
        baseUrl.setOnAction(e -> listModels());
        baseUrl.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && !baseUrl.getText().strip().equals(listedUrl)) listModels();
        });
        modelStatus.getStyleClass().add("dialog-hint");
        Button clear = new Button("New conversation");
        clear.getStyleClass().add("dialog-compact");
        clear.setOnAction(e -> {
            service.reset();
            transcript.clear();
        });
        HBox bar = new HBox(8, new Label("Model"), provider, baseUrl, model, refreshModels, modelStatus, spacer(), clear);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("diagnostics-filter-bar");

        serve.setOnAction(e -> setServing(serve.isSelected()));
        mcpStatus.getStyleClass().add("dialog-hint");
        copySetup.getStyleClass().add("dialog-compact");
        copySetup.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(mcpConfig.claudeCodeCommand());
            Clipboard.getSystemClipboard().setContent(content);
            mcpStatus.setText("Copied. Paste it in a terminal; other clients take the same URL and header.");
        });
        HBox mcpBar = new HBox(8, serve, mcpStatus, spacer(), copySetup);
        mcpBar.setAlignment(Pos.CENTER_LEFT);
        mcpBar.getStyleClass().add("diagnostics-filter-bar");

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

        root.setTop(new VBox(bar, mcpBar));
        root.setCenter(transcript);
        root.setBottom(bottom);
    }

    private static Node spacer() {
        HBox gap = new HBox();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    /** Puts {@code settings} in the controls and asks the provider which models it has. */
    private void show(AssistantSettings settings) {
        Provider kind = settings.providerKind() == Provider.UNKNOWN ? Provider.OLLAMA : settings.providerKind();
        // setValue fires the combo's action, which would show this provider's fresh settings over the saved ones.
        showing = true;
        try {
            provider.setValue(kind);
        } finally {
            showing = false;
        }
        modelLabels.clear();
        model.getItems().clear();
        model.getEditor().setText(settings.model());
        baseUrl.setText(settings.baseUrl());
        baseUrl.setVisible(kind.takesBaseUrl());
        baseUrl.setManaged(kind.takesBaseUrl());
        listModels();
    }

    /**
     * Asks the chosen provider for its models, off the FX thread, and fills the dropdown with the answer. A
     * missing key or a server that is down becomes the status line; the field stays editable either way.
     */
    private void listModels() {
        Provider kind = provider.getValue() == null ? Provider.OLLAMA : provider.getValue();
        String url = kind.takesBaseUrl() ? baseUrl.getText().strip() : "";
        listedUrl = url;
        int request = ++listing;
        modelStatus.setText("Listing models…");
        Thread lister = new Thread(() -> {
            ModelFactory.Listing answer = ModelFactory.models(kind, url, System::getenv);
            Platform.runLater(() -> {
                if (request == listing && !disposed) showModels(kind, answer);
            });
        }, "assistant-models");
        lister.setDaemon(true);
        lister.start();
    }

    private void showModels(Provider kind, ModelFactory.Listing answer) {
        switch (answer) {
            case ModelFactory.Listing.Problem problem -> modelStatus.setText(problem.reason());
            case ModelFactory.Listing.Models listed -> {
                String typed = modelText();
                modelLabels.clear();
                listed.models().forEach(choice -> modelLabels.put(choice.id(), choice.label()));
                model.getItems().setAll(listed.models().stream().map(ModelFactory.Choice::id).toList());
                // What the user chose or typed stays, listed or not; only a blank field takes the newest.
                if (typed.isBlank() && !model.getItems().isEmpty()) model.getEditor().setText(model.getItems().getFirst());
                int count = model.getItems().size();
                modelStatus.setText(count > 0 ? count + (count == 1 ? " model" : " models")
                        : kind == Provider.OLLAMA ? "No models installed. Run: ollama pull qwen3"
                        : "The server listed no chat models. Type one.");
            }
        }
        updateSend();
    }

    private String modelText() {
        String text = model.getEditor().getText();
        return text == null ? "" : text.strip();
    }

    private void updateSend() {
        send.setDisable(working || modelText().isEmpty());
    }

    private AssistantSettings current() {
        Provider kind = provider.getValue() == null ? Provider.OLLAMA : provider.getValue();
        return new AssistantSettings(kind.id(), modelText(), kind.takesBaseUrl() ? baseUrl.getText() : "");
    }

    private void submit() {
        String message = input.getText().strip();
        if (message.isEmpty() || working) return;
        if (modelText().isEmpty()) {
            setStatus("Choose a model first.", true);
            return;
        }
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
        updateSend();
        if (now) setStatus("Working…", false);
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.getStyleClass().removeAll("dialog-status--error", "dialog-status--ok");
        if (error) status.getStyleClass().add("dialog-status--error");
    }
}
