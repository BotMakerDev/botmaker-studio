package com.botmaker.studio.ui.app;

import com.botmaker.core.config.AppVersion;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreLink;
import com.botmaker.studio.services.CoreEdits;
import com.botmaker.studio.ui.app.AssistantPanes.Pane;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Assistant window (spec {@code 2026-11-11-assistant-window-design.md}), opened from the toolbar: the AI tool's
 * terminal ({@link AssistantPane}, the Chat pane), every MCP call the core serves (Calls) and the chosen one (Detail),
 * side by side. Its header's toggles hide any pane but the last one shown; choosing a call shows Detail; the choice is
 * this computer's ({@link AssistantPanes}).
 *
 * <p>The calls come over a channel of the window's own ({@code Request.WatchCalls}), opened the first time the window is
 * shown, so a call made before that is in the core's backlog, not lost. Closing the window hides it: the tool keeps
 * running, as it did in its bottom tab.
 */
final class AssistantWindow {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String WATCHING = "Every MCP call this session, newest last.";
    /** How much of a call's arguments its row shows. */
    private static final int SUMMARY = 90;

    private final AssistantPane chat;
    private final String project;
    private final Stage stage = new Stage();
    private final SplitPane split = new SplitPane();
    private final Map<Pane, ToggleButton> toggles = new EnumMap<>(Pane.class);
    private final Map<Pane, Node> panes = new EnumMap<>(Pane.class);
    private final ToolCalls calls = new ToolCalls();
    private final ObservableList<ToolCalls.Call> rows = FXCollections.observableArrayList();
    private final ListView<ToolCalls.Call> callList = new ListView<>(rows);
    private final Label callsStatus = new Label();
    private final Label detailHeading = new Label();
    private final TextArea detailText = new TextArea();
    private AssistantPanes shown = AssistantPanes.load();
    private boolean built;
    /** The project's window, and what closes this one with it; removed on dispose (it outlives this project). */
    private Window owner;
    private EventHandler<WindowEvent> closeWithOwner;
    /** The channel the calls come over; null until the window is first shown, and once lost. */
    private CoreLink link;
    private boolean watching;
    /** While a told call's row is replaced: its reselection is a redraw, not the user's choice. */
    private boolean redrawing;
    private boolean disposed;

    AssistantWindow(AssistantPane chat, String project) {
        this.chat = chat;
        this.project = project;
    }

    /** Shows the window, in front, the chat given the keyboard; the first time, starts watching the calls. */
    void show(Window owner) {
        if (disposed) return;
        if (!built) build(owner);
        stage.show();
        stage.toFront();
        if (shown.shows(Pane.CHAT)) chat.activate();
        if (!watching) watch();
    }

    /** Closes the window and its channel; the project's window calls this when it goes. The chat is its own to end. */
    void dispose() {
        disposed = true;
        stage.close();
        // The owner is the one primary stage every project's window reuses: left on it, the handler keeps this
        // project's window, chat and terminals alive.
        if (owner != null) owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
        owner = null;
        CoreLink tied = link;
        link = null;
        if (tied != null) tied.close();
    }

    private void build(Window owner) {
        built = true;
        panes.put(Pane.CHAT, chat.node());
        panes.put(Pane.CALLS, callsPane());
        panes.put(Pane.DETAIL, detailPane());

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 10, 6, 10));
        Label title = new Label("Assistant");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(title, spacer);
        for (Pane pane : Pane.values()) {
            ToggleButton toggle = new ToggleButton(pane.displayName());
            toggle.setFocusTraversable(false);
            toggle.setOnAction(e -> setShown(shown.toggled(pane)));
            toggles.put(pane, toggle);
            header.getChildren().add(toggle);
        }

        BorderPane root = new BorderPane(split);
        root.setTop(header);
        applyShown();

        // Not owned, so it can go behind Studio and sit beside it on another screen; it goes when the project's
        // window does, as MigrateWindow does.
        stage.initModality(Modality.NONE);
        if (owner != null) {
            this.owner = owner;
            closeWithOwner = e -> stage.close();
            owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
            if (!Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())) {
                stage.setX(owner.getX() + 80);
                stage.setY(owner.getY() + 60);
            }
        }
        stage.setTitle("Assistant — " + project);
        stage.setScene(ThemedWindows.scene(root, 1280, 760));
    }

    private Node callsPane() {
        callList.setPlaceholder(new Label("No MCP call yet. Each one an assistant makes shows here."));
        callList.setCellFactory(list -> new CallCell());
        callList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == null) return;
            // The same call told again (a step, its end) is redrawn, not chosen: Detail stays as the user left it.
            boolean chosen = !redrawing && (was == null || was.id() != now.id());
            if (chosen && !shown.shows(Pane.DETAIL)) setShown(shown.showing(Pane.DETAIL));
            showDetail(now);
        });
        callsStatus.setWrapText(true);
        callsStatus.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(6, callsStatus, callList);
        VBox.setVgrow(callList, Priority.ALWAYS);
        box.setPadding(new Insets(6));
        box.setMinWidth(0);
        return box;
    }

    private Node detailPane() {
        detailHeading.setWrapText(true);
        detailHeading.setText("Pick a call to see it here.");
        detailText.setEditable(false);
        detailText.setWrapText(true);
        detailText.getStyleClass().add("monospace");
        VBox box = new VBox(6, detailHeading, detailText);
        VBox.setVgrow(detailText, Priority.ALWAYS);
        box.setPadding(new Insets(6));
        box.setMinWidth(0);
        return box;
    }

    private void setShown(AssistantPanes now) {
        if (now.equals(shown)) {
            applyShown();
            return;
        }
        boolean chatAppears = now.shows(Pane.CHAT) && !shown.shows(Pane.CHAT);
        shown = now;
        shown.save();
        applyShown();
        // As selecting the old bottom tab did: the terminal that appears takes the keyboard.
        if (chatAppears) Platform.runLater(chat::activate);
    }

    /** The split holds the shown panes, left to right; each toggle says whether its pane is shown and may hide. */
    private void applyShown() {
        split.getItems().setAll(panes.entrySet().stream().filter(e -> shown.shows(e.getKey()))
                .map(Map.Entry::getValue).toList());
        split.setDividerPositions(switch (split.getItems().size()) {
            case 3 -> new double[] {0.5, 0.75};
            case 2 -> new double[] {0.6};
            default -> new double[0];
        });
        for (Map.Entry<Pane, ToggleButton> each : toggles.entrySet()) {
            Pane pane = each.getKey();
            ToggleButton toggle = each.getValue();
            toggle.setSelected(shown.shows(pane));
            boolean last = !shown.canHide(pane);
            toggle.setDisable(last);
            toggle.setTooltip(new Tooltip(last ? AssistantPanes.LAST_ONE
                    : (shown.shows(pane) ? "Hide " : "Show ") + pane.displayName()));
        }
    }

    /** Opens the window's own channel to the core, off the FX thread, and asks it for every call. */
    private void watch() {
        watching = true;
        callsStatus.setText("Reaching BotMaker's core…");
        // This attempt's own: a lost() told before the opener hands the link over must not be overwritten by it.
        AtomicBoolean lost = new AtomicBoolean();
        Thread opener = new Thread(() -> {
            try {
                CoreLink tied = CoreLink.connect(CoreEdits.coreDir(), AppVersion.label(), CoreLauncher.process(),
                        event -> Platform.runLater(() -> told(event)), call -> { }, new CoreLink.Listener() {
                            @Override
                            public void stopped() {
                                Platform.runLater(() -> callsStatus.setText("BotMaker's core stopped; starting it again…"));
                            }

                            @Override
                            public void restarted() {
                                Platform.runLater(() -> callsStatus.setText(WATCHING));
                            }

                            @Override
                            public void lost(IOException why) {
                                lost.set(true);
                                Platform.runLater(() -> {
                                    callsStatus.setText("Lost BotMaker's core: " + why.getMessage()
                                            + ". Close and reopen the window to watch again.");
                                    link = null;
                                    watching = false;
                                });
                            }
                        });
                // A new core has none of the old one's calls: the list starts again with it.
                tied.rejoin(client -> {
                    Platform.runLater(this::forget);
                    watchOn(client);
                });
                watchOn(tied.client());
                Platform.runLater(() -> {
                    if (disposed || lost.get()) {
                        tied.close();
                        return;
                    }
                    link = tied;
                    callsStatus.setText(WATCHING);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    callsStatus.setText("Could not reach BotMaker's core: " + e.getMessage());
                    watching = false;
                });
            }
        }, "assistant-calls");
        opener.setDaemon(true);
        opener.start();
    }

    /** Asks {@code client} for every call; a refusal is an error, said on the status line. */
    private static void watchOn(CoreClient client) throws IOException {
        if (client.watchCalls() instanceof EditResult.Refused refused) throw new IOException(refused.reason());
    }

    private void forget() {
        calls.clear();
        rows.clear();
        detailHeading.setText("Pick a call to see it here.");
        detailText.clear();
    }

    private void told(CoreEvent event) {
        if (disposed) return;
        ToolCalls.Call changed = calls.told(event);
        if (changed == null) return;
        ToolCalls.Call selected = callList.getSelectionModel().getSelectedItem();
        int at = indexOf(changed.id());
        if (at >= 0) {
            redrawing = true;
            try {
                rows.set(at, changed);
                if (selected != null && selected.id() == changed.id()) {
                    callList.getSelectionModel().select(at);
                    showDetail(changed);
                }
            } finally {
                redrawing = false;
            }
        } else {
            rows.add(changed);
            while (rows.size() > ToolCalls.KEPT) rows.removeFirst();
        }
    }

    private int indexOf(long id) {
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).id() == id) return i;
        return -1;
    }

    private void showDetail(ToolCalls.Call call) {
        detailHeading.setText(heading(call));
        detailText.setText(detail(call));
    }

    /** The call's tool, status, plugin, start and duration, on one line. */
    static String heading(ToolCalls.Call call) {
        StringBuilder line = new StringBuilder(call.started().tool()).append(" · ").append(call.status().displayName());
        if (!call.started().plugin().isEmpty()) line.append(" · ").append(call.started().plugin());
        line.append(" · ").append(CLOCK.format(Instant.ofEpochMilli(call.started().atMillis())));
        if (call.ended() != null) line.append(" · ").append(call.ended().millis()).append(" ms");
        return line.toString();
    }

    /** The call's arguments, its reply's parts, its progress and its device steps, as text (phase 5 draws them). */
    static String detail(ToolCalls.Call call) {
        StringBuilder text = new StringBuilder("Arguments\n").append(call.started().arguments()).append("\n");
        if (call.progress() != null) {
            text.append("\nProgress: ").append(call.progress().command()).append(" ")
                    .append(call.progress().done()).append(" of ").append(call.progress().total()).append("\n");
        }
        ToolCallEnded ended = call.ended();
        if (ended != null) {
            text.append("\nReply\n");
            for (ToolCallEnded.Part part : ended.reply()) {
                text.append(part.kind() == ToolCallEnded.Part.Kind.TEXT ? part.value() : "[image]").append("\n");
            }
        }
        List<ToolCallStep> steps = call.steps();
        if (!steps.isEmpty() || (ended != null && ended.elsewhere() > 0)) text.append("\nDevice steps\n");
        for (ToolCallStep step : steps) {
            text.append(step.ok() ? "✓ " : "✗ ").append(step.device()).append("  ").append(step.command())
                    .append("  ").append(step.millis()).append(" ms");
            if (!step.result().isBlank()) text.append("  → ").append(step.result().strip());
            text.append("\n");
        }
        if (call.moreSteps() > 0) text.append("… and ").append(call.moreSteps()).append(" more steps\n");
        if (ended != null && ended.elsewhere() > 0) text.append("elsewhere: ").append(ended.elsewhere()).append(" steps\n");
        return text.toString();
    }

    /** The row's one-line summary of a call's arguments. */
    static String summary(String arguments) {
        String flat = arguments.replaceAll("\\s+", " ").strip();
        if (flat.equals("{}")) return "";
        return flat.length() > SUMMARY ? flat.substring(0, SUMMARY - 1) + "…" : flat;
    }

    private static final class CallCell extends ListCell<ToolCalls.Call> {
        @Override
        protected void updateItem(ToolCalls.Call call, boolean empty) {
            super.updateItem(call, empty);
            if (empty || call == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(call.status().mark() + "  " + call.started().tool() + "  " + summary(call.started().arguments())
                    + "  " + CLOCK.format(Instant.ofEpochMilli(call.started().atMillis())));
            setTooltip(new Tooltip(call.status().displayName()));
        }
    }
}
