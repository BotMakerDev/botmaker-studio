package com.botmaker.studio.ui.app;

import com.botmaker.studio.assist.AiTool;
import com.botmaker.studio.assist.McpConfig;
import com.botmaker.studio.assist.McpEndpoint;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.ui.app.terminal.TerminalView;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Assistant bottom tab: an AI command-line tool — Claude Code, Codex, Gemini CLI, opencode — running in a
 * terminal inside Studio, wired to Studio's MCP endpoint and denied direct file edits ({@link AiTool}).
 *
 * <p>It used to be a chat over LangChain4j with a model the user picked by provider and key. That could not
 * use a Claude subscription, which only the {@code claude} CLI can, and it was a second tool loop beside the
 * MCP one. Now the tool is the user's own, logged in as they logged it in; what Studio adds is the endpoint,
 * started when the first tool opens, and the deny list. Every change the tool makes goes through
 * {@code McpTools}: compiled first, one undo step each.
 */
final class AssistantPane {

    private static final String SHELL = System.getenv("SHELL");

    private final StudioContext ctx;
    private final Path projectDir;
    private final BorderPane root = new BorderPane();
    private final ComboBox<AiTool> tool = new ComboBox<>();
    private final Button open = new Button("Open");
    private final CheckBox allowShell = new CheckBox("Allow shell commands");
    private final Label status = new Label();
    private final CheckBox serve = new CheckBox("Serve to other MCP clients");
    private final Label mcpStatus = new Label();
    private final Button copySetup = new Button("Copy Claude Code setup");
    private final TabPane sessions = new TabPane();
    /** Shown instead of {@link #sessions} while no tool is open. */
    private StackPane placeholder;
    /** Where each tool was found; absent until the search that runs at construction answers. */
    private final Map<AiTool, Optional<Path>> found = new EnumMap<>(AiTool.class);
    private McpConfig mcpConfig = McpConfig.load();
    private McpEndpoint endpoint;
    /** Callers waiting for the endpoint that is starting now; null when none is starting. */
    private List<Consumer<McpEndpoint>> waiting;
    /** This window's private directory for the tools' config files (they hold the token); made on first use. */
    private Path configDir;
    private boolean disposed;

    AssistantPane(StudioContext ctx) {
        this.ctx = ctx;
        this.projectDir = ctx.config().projectPath();
        build();
        serve.setSelected(mcpConfig.enabled());
        copySetup.setDisable(!mcpConfig.enabled());
        if (mcpConfig.enabled()) ensureEndpoint(e -> { });
        else mcpStatus.setText("Opening a tool starts the endpoint; tick this to keep it for other clients.");
        findTools();
    }

    Node node() {
        return root;
    }

    /** The tab was shown: give the open tool the keyboard. */
    void activate() {
        Tab selected = sessions.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getUserData() instanceof TerminalView view) view.focus();
    }

    /** Ends every tool, stops the endpoint and deletes the config files. The window calls this when it goes. */
    void dispose() {
        disposed = true;
        for (Tab tab : List.copyOf(sessions.getTabs())) {
            if (tab.getUserData() instanceof TerminalView view) view.dispose();
        }
        sessions.getTabs().clear();
        stopEndpoint();
        deleteConfigDir();
    }

    private void build() {
        tool.getItems().setAll(AiTool.offered());
        tool.setValue(AiTool.CLAUDE_CODE);
        tool.setCellFactory(list -> new ToolCell());
        tool.setButtonCell(new ToolCell());
        tool.setOnAction(e -> updateOpen());
        open.setDefaultButton(false);
        open.setOnAction(e -> openTool());
        allowShell.setOnAction(e -> updateOpen());
        allowShell.setTooltip(new Tooltip("A shell command can write files too, so it is off by default. Codex "
                + "always runs in its read-only sandbox."));
        status.getStyleClass().add("dialog-hint");
        HBox bar = new HBox(8, new Label("AI tool"), tool, open, allowShell, status, spacer());
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("diagnostics-filter-bar");

        serve.setOnAction(e -> setServing(serve.isSelected()));
        mcpStatus.getStyleClass().add("dialog-hint");
        copySetup.getStyleClass().add("dialog-compact");
        copySetup.setTooltip(new Tooltip("For a terminal outside Studio. Other clients take the same URL and header."));
        copySetup.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(mcpConfig.claudeCodeCommand());
            Clipboard.getSystemClipboard().setContent(content);
            mcpStatus.setText("Copied. It carries the token: paste it only in your own terminal.");
        });
        HBox mcpBar = new HBox(8, serve, mcpStatus, spacer(), copySetup);
        mcpBar.setAlignment(Pos.CENTER_LEFT);
        mcpBar.getStyleClass().add("diagnostics-filter-bar");

        sessions.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        sessions.getTabs().addListener((ListChangeListener<Tab>) c -> sessionsChanged());
        Label empty = new Label("Pick an AI tool and press Open. It runs here, logged in as you, and changes the bot "
                + "only through Studio's MCP tools: every edit compiled first, each one undoable.");
        empty.setWrapText(true);
        empty.getStyleClass().add("dialog-hint");
        placeholder = new StackPane(empty);
        root.setTop(new VBox(bar, mcpBar));
        root.setCenter(placeholder);
        updateOpen();
    }

    /** The last tool closed: back to the hint, and the endpoint stops unless it is kept for other clients. */
    private void sessionsChanged() {
        boolean none = sessions.getTabs().isEmpty();
        root.setCenter(none ? placeholder : sessions);
        if (none && !serve.isSelected()) stopEndpoint();
    }

    private static Node spacer() {
        HBox gap = new HBox();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    /** Looks each tool up off the FX thread: {@code PATH} first, then the login shell's. */
    private void findTools() {
        status.setText("Looking for AI tools…");
        Thread finder = new Thread(() -> {
            Map<AiTool, Optional<Path>> answers = new EnumMap<>(AiTool.class);
            Path home = Path.of(System.getProperty("user.home"));
            for (AiTool candidate : AiTool.offered()) {
                Optional<Path> where = candidate.locate(System.getenv("PATH"), home);
                answers.put(candidate, where.isPresent() ? where : candidate.locateByLoginShell(SHELL));
            }
            Platform.runLater(() -> {
                if (disposed) return;
                found.putAll(answers);
                if (found.getOrDefault(tool.getValue(), Optional.empty()).isEmpty()) {
                    AiTool.offered().stream().filter(t -> found.get(t).isPresent()).findFirst()
                            .ifPresent(tool::setValue);
                }
                status.setText("");
                updateOpen();
                // A ComboBox caches its cells; a new list is how they redraw with what was found.
                tool.getItems().setAll(AiTool.offered());
            });
        }, "ai-tools-find");
        finder.setDaemon(true);
        finder.start();
    }

    private void updateOpen() {
        AiTool chosen = tool.getValue();
        Optional<Path> where = chosen == null ? null : found.get(chosen);
        open.setDisable(where == null || where.isEmpty());
        if (where != null && where.isEmpty() && chosen != null) {
            status.setText(chosen.displayName() + " is not installed. Install: " + chosen.installHint());
        } else if (chosen != null && where != null) {
            status.setText(chosen.guard(allowShell.isSelected()));
        }
    }

    private void openTool() {
        AiTool chosen = tool.getValue();
        Optional<Path> where = chosen == null ? Optional.empty() : found.getOrDefault(chosen, Optional.empty());
        if (where.isEmpty()) return;
        boolean shell = allowShell.isSelected();
        open.setDisable(true);
        status.setText("Starting Studio's MCP endpoint…");
        ensureEndpoint(started -> {
            open.setDisable(false);
            if (started == null) return;
            try {
                AiTool.Launch launch = chosen.launch(where.get(),
                        new AiTool.Connection(started.url(), mcpConfig.token(), configDir()), shell);
                addSession(chosen, launch, shell);
            } catch (IOException | RuntimeException e) {
                status.setText("Could not prepare " + chosen.displayName() + ": " + e.getMessage());
            }
        });
    }

    private void addSession(AiTool chosen, AiTool.Launch launch, boolean shell) {
        long same = sessions.getTabs().stream().filter(t -> t.getText().startsWith(chosen.displayName())).count();
        String title = chosen.displayName() + (same == 0 ? "" : " " + (same + 1));
        Tab tab = new Tab(title);
        TerminalView view = new TerminalView(new TerminalView.Launch(
                AiTool.viaLoginShell(SHELL, launch.command()), projectDir, launch.env()),
                code -> tab.setText(title + " (exited)"));
        tab.setContent(view.node());
        tab.setUserData(view);
        tab.setTooltip(new Tooltip("Connected to Studio via MCP · " + chosen.guard(shell)));
        tab.setOnClosed(e -> view.dispose());
        sessions.getTabs().add(tab);
        sessions.getSelectionModel().select(tab);
        status.setText("Connected via MCP · " + chosen.guard(shell));
        view.focus();
    }

    /**
     * Calls {@code then} on the FX thread with the running endpoint, starting it first if needed — off the FX
     * thread, since a taken port can be a bind timeout — or with null, and the reason on the status line, when
     * it cannot start.
     */
    private void ensureEndpoint(Consumer<McpEndpoint> then) {
        if (endpoint != null) {
            then.accept(endpoint);
            return;
        }
        if (waiting != null) {
            waiting.add(then);
            return;
        }
        waiting = new ArrayList<>(List.of(then));
        mcpStatus.setText("Starting…");
        McpConfig starting = mcpConfig;
        Thread starter = new Thread(() -> {
            McpEndpoint started = null;
            String failure = null;
            try {
                started = McpEndpoint.start(starting.port(), starting.token(), new LiveEditorFile(ctx));
            } catch (Exception e) {
                failure = "Could not listen on port " + starting.port() + ": " + e.getMessage();
            }
            McpEndpoint result = started;
            String reason = failure;
            Platform.runLater(() -> {
                List<Consumer<McpEndpoint>> callers = waiting;
                waiting = null;
                if (disposed) {
                    // A start that finishes after the window closed must let go of the port, or the next
                    // window's endpoint cannot bind it.
                    if (result != null) result.close();
                    return;
                }
                if (result == null) {
                    mcpStatus.setText(reason);
                    status.setText(reason);
                    serve.setSelected(false);
                } else {
                    endpoint = result;
                    mcpStatus.setText("Serving " + result.url() + " (token required)");
                }
                callers.forEach(c -> c.accept(result));
            });
        }, "mcp-start");
        starter.setDaemon(true);
        starter.start();
    }

    /** Keeps the endpoint on for clients outside Studio, and remembers the choice. */
    private void setServing(boolean on) {
        mcpConfig = mcpConfig.withEnabled(on);
        mcpConfig.save();
        copySetup.setDisable(!on);
        if (on) {
            ensureEndpoint(e -> { });
        } else if (sessions.getTabs().isEmpty()) {
            stopEndpoint();
            mcpStatus.setText("Opening a tool starts the endpoint; tick this to keep it for other clients.");
        }
    }

    private void stopEndpoint() {
        if (endpoint == null) return;
        endpoint.close();
        endpoint = null;
        mcpStatus.setText("Stopped.");
    }

    private Path configDir() throws IOException {
        if (configDir == null) {
            try {
                configDir = Files.createTempDirectory("botmaker-ai-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                configDir = Files.createTempDirectory("botmaker-ai-");
            }
        }
        return configDir;
    }

    private void deleteConfigDir() {
        if (configDir == null) return;
        try (Stream<Path> walk = Files.walk(configDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort: what is left is the same token mcp.json keeps, readable by the user only.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
        configDir = null;
    }

    /** A tool's name, greyed with "(not installed)" when the search did not find it. */
    private final class ToolCell extends ListCell<AiTool> {
        @Override
        protected void updateItem(AiTool item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setDisable(false);
                return;
            }
            Optional<Path> where = found.get(item);
            boolean missing = where != null && where.isEmpty();
            setText(item.displayName() + (missing ? " (not installed)" : ""));
            setOpacity(missing ? 0.55 : 1.0);
        }
    }
}
