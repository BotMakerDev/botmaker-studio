package com.botmaker.studio.ui.app;

import com.botmaker.core.config.AppVersion;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreLink;
import com.botmaker.studio.services.CoreEdits;
import com.botmaker.studio.ui.app.AssistantPanes.Pane;
import com.botmaker.studio.ui.app.ToolCalls.Status;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.transformation.FilteredList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
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
import javafx.util.Duration;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * The Assistant window (spec {@code 2026-11-11-assistant-window-design.md}), opened from the toolbar: the AI tool's
 * terminal ({@link AssistantPane}, the Chat pane), every MCP call the core serves (Calls, with its tool checklist and
 * filters) and the chosen one (Detail, drawn by {@link CallDetail}), side by side. Its header's toggles hide any pane
 * but the last one shown; choosing a call shows Detail; the choice is this computer's ({@link AssistantPanes}).
 *
 * <p>The calls come over a channel of the window's own ({@code Request.WatchCalls}), opened the first time the window is
 * shown, so a call made before that is in the core's backlog, not lost. Closing the window hides it: the tool keeps
 * running, as it did in its bottom tab.
 */
final class AssistantWindow {

    private static final String WATCHING = "Every MCP call this session, newest last.";
    private static final String PICK = "Pick a call to see it here.";
    /** How much of a call's arguments its row shows. */
    private static final int SUMMARY = 90;
    /** How often the checklist recounts while sorted by the last 5 minutes. */
    private static final Duration RECOUNT = Duration.seconds(15);
    /** How often a running call's Detail is drawn again as its steps come. */
    private static final Duration REDRAW = Duration.millis(250);

    private final AssistantPane chat;
    private final String project;
    private final CallDetail drawer;
    private final Stage stage = new Stage();
    private final SplitPane split = new SplitPane();
    private final Map<Pane, ToggleButton> toggles = new EnumMap<>(Pane.class);
    private final Map<Pane, Node> panes = new EnumMap<>(Pane.class);
    private final ToolCalls calls = new ToolCalls();
    private final FilteredList<ToolCalls.Call> shownCalls = new FilteredList<>(calls.view(), CallFilter.ALL);
    private final ListView<ToolCalls.Call> callList = new ListView<>(shownCalls);
    private final ToolChecklist checklist = new ToolChecklist(this::refilter, this::recount);
    private final ComboBox<Choice<String>> pluginChoice = new ComboBox<>();
    private final ComboBox<Choice<String>> deviceChoice = new ComboBox<>();
    private final ComboBox<Choice<Status>> statusChoice = new ComboBox<>();
    private final TextField search = new TextField();
    private final Label callsStatus = new Label();
    private final Label detailHeading = new Label(PICK);
    private final ScrollPane detailBody = new ScrollPane();
    private final Timeline recount = new Timeline(new KeyFrame(RECOUNT, e -> recount()));
    /** Draws the shown call again once its steps have paused for a moment ({@link #REDRAW}). */
    private final PauseTransition redraw = new PauseTransition(REDRAW);
    private CallFilter filter = CallFilter.ALL;
    private AssistantPanes shown = AssistantPanes.load();
    private boolean built;
    /** The project's window, and what closes this one with it; removed on dispose (it outlives this project). */
    private Window owner;
    private EventHandler<WindowEvent> closeWithOwner;
    /** The channel the calls come over; null until the window is first shown, and once lost. */
    private CoreLink link;
    private boolean watching;
    /** While the list changes under the selection: a reselection then is a redraw, not the user's choice. */
    private boolean redrawing;
    private boolean disposed;
    /** The call Detail shows, by id; -1 for none. */
    private long detailed = -1;

    /** {@code opener} opens a method an edit changed, from Detail's links. */
    AssistantWindow(AssistantPane chat, String project, CallDetail.MethodOpener opener) {
        this.chat = chat;
        this.project = project;
        this.drawer = new CallDetail(opener);
        recount.setCycleCount(Timeline.INDEFINITE);
        redraw.setOnFinished(e -> calls.find(detailed).ifPresent(this::showDetail));
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
        recount.stop();
        redraw.stop();
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
        stage.setOnShown(e -> recount.play());
        stage.setOnHidden(e -> recount.stop());
    }

    // ---- Calls ------------------------------------------------------------------------------------------------

    private Node callsPane() {
        callList.setPlaceholder(new Label("No MCP call yet. Each one an assistant makes shows here."));
        callList.setCellFactory(list -> new CallCell());
        callList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == null || redrawing) return;
            // The same call told again (a step, its end) is redrawn, not chosen: Detail stays as the user left it.
            if (was != null && was.id() == now.id()) return;
            if (!shown.shows(Pane.DETAIL)) setShown(shown.showing(Pane.DETAIL));
            showDetail(now);
        });

        pluginChoice.setOnAction(e -> {
            if (!redrawing && pluginChoice.getValue() != null) refilter(f -> f.withPlugin(pluginChoice.getValue().value()));
        });
        deviceChoice.setOnAction(e -> {
            if (!redrawing && deviceChoice.getValue() != null) refilter(f -> f.withDevice(deviceChoice.getValue().value()));
        });
        List<Choice<Status>> statuses = new ArrayList<>(List.of(new Choice<>(Optional.empty(), "Any status")));
        for (Status status : Status.values()) statuses.add(new Choice<>(Optional.of(status), status.mark() + " " + status.displayName()));
        statusChoice.getItems().setAll(statuses);
        statusChoice.setValue(statuses.getFirst());
        statusChoice.setOnAction(e -> {
            if (statusChoice.getValue() != null) refilter(f -> f.withStatus(statusChoice.getValue().value()));
        });
        search.setPromptText("Search tool, arguments, reply");
        search.textProperty().addListener((o, was, now) -> refilter(f -> f.withSearch(now)));
        HBox.setHgrow(search, Priority.ALWAYS);
        choices();
        HBox filters = new HBox(6, pluginChoice, deviceChoice, statusChoice, search);
        filters.setAlignment(Pos.CENTER_LEFT);

        TitledPane tools = new TitledPane("Tools", checklist.node());
        tools.setAnimated(false);

        callsStatus.setWrapText(true);
        callsStatus.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(6, callsStatus, tools, filters, callList);
        VBox.setVgrow(callList, Priority.ALWAYS);
        box.setPadding(new Insets(6));
        box.setMinWidth(0);
        return box;
    }

    /** The filter changed by {@code change}: the list and the checklist follow. */
    private void refilter(UnaryOperator<CallFilter> change) {
        CallFilter now = change.apply(filter);
        if (now.equals(filter)) return;
        filter = now;
        redrawing = true;
        try {
            shownCalls.setPredicate(filter);
            reselect();
        } finally {
            redrawing = false;
        }
        recount();
    }

    /** The checklist counted again, in its sort's order. */
    private void recount() {
        checklist.show(calls.tallies(checklist.sort(), System.currentTimeMillis()), filter);
    }

    /** The plugin and device choices, as the calls name them, each kept as chosen. */
    private void choices() {
        redrawing = true;
        try {
            List<Choice<String>> plugins = new ArrayList<>(List.of(new Choice<>(Optional.empty(), "Any plugin")));
            for (String plugin : calls.plugins()) {
                plugins.add(new Choice<>(Optional.of(plugin), plugin.equals(ToolCalls.CORE) ? "BotMaker core" : plugin));
            }
            keep(pluginChoice, plugins, filter.plugin());
            List<Choice<String>> devices = new ArrayList<>(List.of(new Choice<>(Optional.empty(), "Any device")));
            for (String device : calls.devices()) devices.add(new Choice<>(Optional.of(device), device));
            keep(deviceChoice, devices, filter.device());
        } finally {
            redrawing = false;
        }
    }

    private static <T> void keep(ComboBox<Choice<T>> box, List<Choice<T>> items, Optional<T> chosen) {
        if (box.getItems().equals(items)) return;
        box.getItems().setAll(items);
        box.setValue(items.stream().filter(c -> c.value().equals(chosen)).findFirst().orElse(items.getFirst()));
    }

    /** The call Detail shows selected again, if the list still shows it. */
    private void reselect() {
        if (detailed < 0) return;
        for (int i = 0; i < shownCalls.size(); i++) {
            if (shownCalls.get(i).id() == detailed) {
                callList.getSelectionModel().select(i);
                return;
            }
        }
        callList.getSelectionModel().clearSelection();
    }

    // ---- Detail -----------------------------------------------------------------------------------------------

    private Node detailPane() {
        detailHeading.setWrapText(true);
        detailHeading.setStyle("-fx-font-weight: bold;");
        detailHeading.setMinHeight(Region.USE_PREF_SIZE);
        detailBody.setFitToWidth(true);
        VBox box = new VBox(6, detailHeading, detailBody);
        VBox.setVgrow(detailBody, Priority.ALWAYS);
        box.setPadding(new Insets(6));
        box.setMinWidth(0);
        return box;
    }

    private void showDetail(ToolCalls.Call call) {
        boolean same = call.id() == detailed;
        double scrolled = detailBody.getVvalue();
        detailed = call.id();
        detailHeading.setText(CallDetail.heading(call));
        detailBody.setContent(drawer.draw(CallDetail.blocks(call, calls)));
        // A step told to the call shown keeps the reader where they were.
        if (same) Platform.runLater(() -> detailBody.setVvalue(scrolled));
    }

    // ---- panes ------------------------------------------------------------------------------------------------

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
            case 3 -> new double[] {0.45, 0.72};
            case 2 -> new double[] {0.55};
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

    // ---- the channel ------------------------------------------------------------------------------------------

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
        redrawing = true;
        try {
            calls.clear();
        } finally {
            redrawing = false;
        }
        detailed = -1;
        redraw.stop();
        detailHeading.setText(PICK);
        detailBody.setContent(null);
        choices();
        recount();
    }

    private void told(CoreEvent event) {
        if (disposed) return;
        ToolCalls.Call changed;
        redrawing = true;
        try {
            changed = calls.told(event);
            if (changed != null && changed.id() == detailed) reselect();
        } finally {
            redrawing = false;
        }
        if (changed == null) return;
        if (changed.id() == detailed) {
            // A call's end is drawn at once; its steps at most once a pulse of REDRAW, so a call of hundreds of steps
            // does not rebuild Detail at each.
            if (event instanceof ToolCallEnded) {
                redraw.stop();
                showDetail(changed);
            } else if (redraw.getStatus() != Animation.Status.RUNNING) {
                redraw.playFromStart();
            }
        } else if (event instanceof ToolCallEnded && detailed >= 0) {
            // A shown tap waits for the frame after it: a later call's screenshot may be it.
            calls.find(detailed)
                    .filter(c -> c.ended() != null && !c.ended().extras().tap().isEmpty() && c.image().isEmpty())
                    .ifPresent(this::showDetail);
        }
        // A count, a running mark or a new plugin or device changes only as a call starts or ends, or a step names one.
        if (event instanceof ToolCallStarted || event instanceof ToolCallEnded) recount();
        if (!(event instanceof ToolCallEnded)) choicesIfNew(changed);
    }

    private void choicesIfNew(ToolCalls.Call changed) {
        boolean newPlugin = pluginChoice.getItems().stream().noneMatch(c -> c.value().equals(Optional.of(changed.plugin())));
        boolean newDevice = changed.devices().stream().anyMatch(d -> deviceChoice.getItems().stream()
                .noneMatch(c -> c.value().equals(Optional.of(d))));
        if (newPlugin || newDevice) choices();
    }

    /** The row's one-line summary of a call's arguments. */
    static String summary(String arguments) {
        String flat = arguments.replaceAll("\\s+", " ").strip();
        if (flat.equals("{}")) return "";
        return flat.length() > SUMMARY ? flat.substring(0, SUMMARY - 1) + "…" : flat;
    }

    /** A filter choice: a value, or any ({@code value} empty), and how it is shown. */
    private record Choice<T>(Optional<T> value, String label) {
        Choice {
            Objects.requireNonNull(value);
        }

        @Override
        public String toString() {
            return label;
        }
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
            setText(call.status().mark() + "  " + call.tool() + "  " + summary(call.started().arguments())
                    + "  " + CallDetail.CLOCK.format(Instant.ofEpochMilli(call.started().atMillis())));
            setTooltip(new Tooltip(call.status().displayName()
                    + (call.plugin().isEmpty() ? "" : " · " + call.plugin())
                    + (call.devices().isEmpty() ? "" : " · " + String.join(", ", call.devices()))));
        }
    }
}
