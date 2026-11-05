package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.studio.services.RunLogOnBus;
import com.botmaker.core.assist.AiTool;
import com.botmaker.core.assist.McpConfig;
import com.botmaker.core.assist.McpEndpoint;
import com.botmaker.core.assist.McpTools;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.studio.assist.CoreTools;
import com.botmaker.studio.assist.StudioToolGroup;
import com.botmaker.core.assist.RunLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.core.project.vcs.Checkpoints;
import com.botmaker.core.project.vcs.VersionOrigin;
import com.botmaker.studio.ui.app.terminal.TerminalView;
import com.botmaker.studio.ui.app.trial.Trials;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    /** Where an MCP client reaches this project's tools, served through the core; null while they are not. */
    private CoreLauncher.Address endpoint;
    /** What runs and what it printed, as the served tools read it; closed when they go. */
    private RunLog runLog;
    /** What the served tools ask of this window; with {@link #runLog}. */
    private StudioBridge driver;
    /** ▶ Try and ▶ Run on its own, which the endpoint's run tools ask. */
    private final Trials trials;
    /** Callers waiting for the tools being registered now; null when none are. */
    private List<Consumer<CoreLauncher.Address>> waiting;
    /** This window's private directory for the tools' config files (they hold the token); made on first use. */
    private Path configDir;
    private boolean disposed;

    AssistantPane(StudioContext ctx, Trials trials) {
        this.ctx = ctx;
        this.trials = trials;
        this.projectDir = ctx.config().projectPath();
        build();
        serve.setSelected(mcpConfig.enabled());
        copySetup.setDisable(!mcpConfig.enabled());
        // A dev Studio Claude drives (BOTMAKER_MCP_UI) serves each project it opens, as the home serves the bot list.
        if (mcpConfig.enabled() || StudioToolGroup.INSTANCE.driven()) ensureEndpoint(e -> { });
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
        status.getStyleClass().add(StyleClasses.DIALOG_HINT);
        HBox bar = new HBox(8, new Label("AI tool"), tool, open, allowShell, status, spacer());
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("diagnostics-filter-bar");

        serve.setOnAction(e -> setServing(serve.isSelected()));
        mcpStatus.getStyleClass().add(StyleClasses.DIALOG_HINT);
        copySetup.getStyleClass().add(StyleClasses.DIALOG_COMPACT);
        copySetup.setTooltip(new Tooltip("Starts Claude Code in a terminal opened in the bot's folder, with file "
                + "edits denied so it changes the bot only through Studio. Other clients take the same URL and "
                + "header."));
        copySetup.setOnAction(e -> {
            String command;
            try {
                command = mcpConfig.claudeCodeCommand(McpConfig.CLAUDE_FILE);
            } catch (IOException ex) {
                mcpStatus.setText("Could not write " + McpConfig.CLAUDE_FILE + ": " + ex.getMessage());
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(command);
            Clipboard.getSystemClipboard().setContent(content);
            mcpStatus.setText("Copied. Run it in a terminal in the bot's folder.");
        });
        HBox mcpBar = new HBox(8, serve, mcpStatus, spacer(), copySetup);
        mcpBar.setAlignment(Pos.CENTER_LEFT);
        mcpBar.getStyleClass().add("diagnostics-filter-bar");

        sessions.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        sessions.getTabs().addListener((ListChangeListener<Tab>) c -> sessionsChanged());
        Label empty = new Label("Pick an AI tool and press Open. It runs here, logged in as you, and changes the bot "
                + "only through Studio's MCP tools: every edit compiled first, each one undoable.");
        empty.setWrapText(true);
        empty.getStyleClass().add(StyleClasses.DIALOG_HINT);
        placeholder = new StackPane(empty);
        root.setTop(new VBox(bar, mcpBar));
        root.setCenter(placeholder);
        updateOpen();
    }

    /** The last tool closed: back to the hint, and the endpoint stops unless it is kept for other clients. */
    private void sessionsChanged() {
        boolean none = sessions.getTabs().isEmpty();
        root.setCenter(none ? placeholder : sessions);
        if (none && !serve.isSelected() && !StudioToolGroup.INSTANCE.driven()) stopEndpoint();
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
                        new AiTool.Connection(McpEndpoint.url(started.port()), started.token(), configDir()), shell);
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
        // A session is bracketed by two versions, so what it changed can be read or undone as one span
        // (39-versions.md §3). The second is taken once, whichever comes first: the tool exits, or its tab goes.
        checkpoint(VersionOrigin.AUTO, "Before " + title);
        boolean[] ended = {false};
        Runnable end = () -> {
            if (ended[0]) return;
            ended[0] = true;
            checkpoint(VersionOrigin.AI, "AI: " + title);
        };
        TerminalView view = new TerminalView(new TerminalView.Launch(
                AiTool.viaLoginShell(SHELL, launch.command()), projectDir, launch.env()),
                code -> {
                    tab.setText(title + " (exited)");
                    end.run();
                });
        tab.setContent(view.node());
        tab.setUserData(view);
        tab.setTooltip(new Tooltip("Connected to Studio via MCP · " + chosen.guard(shell)));
        tab.setOnClosed(e -> {
            view.dispose();
            end.run();
        });
        sessions.getTabs().add(tab);
        sessions.getSelectionModel().select(tab);
        status.setText("Connected via MCP · " + chosen.guard(shell));
        view.focus();
    }

    /** A version of the project as the editor holds it; the snapshot is read here, on the FX thread. */
    private void checkpoint(VersionOrigin origin, String label) {
        if (disposed) return;
        Checkpoints.takeLater(ctx.config().projectPath(), ctx.state().snapshot(), origin, label);
    }

    /**
     * Calls {@code then} on the FX thread with where an MCP client reaches this project's tools, registering them with
     * the core first if needed (plan phase 10d) — off the FX thread, since the core may have to be started — or with
     * null, and the reason on the status line, when no core could be had.
     */
    private void ensureEndpoint(Consumer<CoreLauncher.Address> then) {
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
        runLog = RunLogOnBus.listen(ctx.eventBus(), new RunLog(Clock.systemUTC(), ctx.runs()::isRunning));
        driver = new StudioBridge(ctx, trials, runLog,
                () -> root.getScene() == null ? null : root.getScene().getWindow());
        registerTools().thenAccept(at -> Platform.runLater(() -> {
            List<Consumer<CoreLauncher.Address>> callers = waiting;
            waiting = null;
            if (disposed) return;
            CoreLauncher.Address result = at.orElse(null);
            if (result == null) {
                String reason = "BotMaker's core could not be reached; core.log in the cache folder says why.";
                mcpStatus.setText(reason);
                status.setText(reason);
                serve.setSelected(false);
                CoreTools.backHome();
                closeTools();
            } else {
                endpoint = result;
                mcpStatus.setText("Serving " + McpEndpoint.url(result.port()) + " (token required)");
            }
            callers.forEach(c -> c.accept(result));
        }));
    }

    /** This project's tools and its plugins' as they are now, registered with the core; where they are reached. */
    private CompletableFuture<Optional<CoreLauncher.Address>> registerTools() {
        McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());
        StudioBridge projectDriver = driver;
        return CoreTools.project(McpTools.all(json, projectDriver, runLog, StudioToolGroup.INSTANCE, () -> {
            // A tool added or removed a plugin: its tools are served anew, on FX, which owns driver and runLog.
            Platform.runLater(() -> {
                if (driver == projectDriver) registerTools();
            });
        }), projectDriver, StudioToolGroup.INSTANCE);
    }

    /** Keeps the endpoint on for clients outside Studio, and remembers the choice. */
    private void setServing(boolean on) {
        mcpConfig = mcpConfig.withEnabled(on);
        mcpConfig.save();
        copySetup.setDisable(!on);
        if (on) {
            ensureEndpoint(e -> { });
        } else if (sessions.getTabs().isEmpty() && !StudioToolGroup.INSTANCE.driven()) {
            stopEndpoint();
            mcpStatus.setText("Opening a tool starts the endpoint; tick this to keep it for other clients.");
        }
    }

    /** This project's tools go from the core, and the home's come back; the core's own go too, as nothing admits them. */
    private void stopEndpoint() {
        if (driver == null) return;
        CoreTools.backHome();
        closeTools();
        mcpStatus.setText("Stopped.");
    }

    private void closeTools() {
        endpoint = null;
        driver = null;
        if (runLog != null) runLog.close();
        runLog = null;
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
