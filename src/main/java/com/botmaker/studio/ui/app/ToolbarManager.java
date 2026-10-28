package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.toolbar.ActionContext;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostActionContext;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.PluginHost.PluginBar;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public class ToolbarManager {

    /**
     * How many rows either toolbar group may wrap onto before the rest goes into its {@code »} menu. Two,
     * because the bar sits between the menu bar and the canvas: a third row is already more toolbar than most
     * windows have room for above the code.
     */
    private static final int TOOLBAR_MAX_ROWS = 2;

    private final EventBus eventBus;
    private final ProjectSettingsService settings;
    // Controls
    private Button undoButton, redoButton;
    private Button runButton, debugButton, followButton, unifiedStopButton;
    private Button stepOverButton, continueButton;
    /** The plugin tabs, most recent first, while no project is open to keep them. */
    private List<String> sessionTabs = List.of();

    /** Opens the Project Settings dialog; the same action the Project menu fires. */
    private Runnable onProjectSettings;
    /** Opens the Parameters dialog; the same action the Project menu fires. */
    private Runnable onParameters;

    /** Turns the reader-mode preview on and off, and what it is when the bar is built. */
    private Runnable onPreviewAsUser;
    /** Opens the program-shape overlay authoring editor; wired by {@link UIManager}. */
    private Runnable onOverlayEditor;
    /** Rebuilds the bar in place after a plugin tab is picked; wired by {@link UIManager}. */
    private Runnable onRebuildBar;

    private enum AppState { IDLE, RUNNING, DEBUGGING }
    private AppState currentAppState = AppState.IDLE;

    public ToolbarManager(EventBus eventBus, ProjectSettingsService settings) {
        this.eventBus = eventBus;
        this.settings = settings;
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        // Every item re-reads itself on a settings change: a supplier is a field read by contract, so
        // refreshing the bar costs less than knowing which button a settings change touched.
        eventBus.subscribe(CoreApplicationEvents.SettingsChangedEvent.class, e -> refreshItems(), true);
        // A plugin's write to the bot's Java — its game or capture source is a @Param now — relabels the bar too.
        eventBus.subscribe(CoreApplicationEvents.FilesRewrittenEvent.class, e -> refreshItems(), true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> setAppState(AppState.RUNNING), true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> setAppState(AppState.IDLE), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionEvent.class, e -> {
            switch (e) {
                case CoreApplicationEvents.DebugSessionStartedEvent ignored -> setAppState(AppState.DEBUGGING);
                case CoreApplicationEvents.DebugSessionFinishedEvent ignored -> setAppState(AppState.IDLE);
                case CoreApplicationEvents.DebugSessionPausedEvent ignored -> updateDebugControls(true);
                case CoreApplicationEvents.DebugSessionResumedEvent ignored -> updateDebugControls(false);
            }
        }, true);
        eventBus.subscribe(CoreApplicationEvents.HistoryStateChangedEvent.class, event -> {
            if (undoButton != null) {
                undoButton.setDisable(!event.canUndo());
                undoButton.setTooltip(new Tooltip(tip("Undo", event.canUndo() ? event.undoLabel() : "", "Ctrl+Z")));
            }
            if (redoButton != null) {
                redoButton.setDisable(!event.canRedo());
                redoButton.setTooltip(new Tooltip(tip("Redo", event.canRedo() ? event.redoLabel() : "", "Ctrl+Y")));
            }
        }, true);
    }

    /** "Undo the change to loadTargets, in 4 files (Ctrl+Z)" — or just the verb, when there is nothing to say. */
    private static String tip(String verb, String what, String accelerator) {
        String head = what == null || what.isBlank() ? verb : verb + " " + what;
        return head + " (" + accelerator + ")";
    }

    /**
     * Creates the left-side group: Undo and Redo, and nothing else. Compile used to live here and now sits with
     * Run in {@link #createExecutionGroup()} — it is the first step of the same "make this bot go" sequence, and
     * keeping it here made the left group wide enough to matter when the window narrows. Undo/Redo stay because
     * they're two glyph-sized buttons that edit code rather than run it.
     */
    public HBox createEditGroup() {
        undoButton = new Button("↶");
        undoButton.setTooltip(new Tooltip("Undo (Ctrl+Z)"));
        undoButton.setDisable(true);
        undoButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.UndoRequestedEvent()));

        redoButton = new Button("↷");
        redoButton.setTooltip(new Tooltip("Redo (Ctrl+Y)"));
        redoButton.setDisable(true);
        redoButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.RedoRequestedEvent()));

        HBox group = new HBox(5, undoButton, redoButton);
        group.setAlignment(Pos.CENTER_LEFT);
        group.setPadding(new Insets(0, 10, 0, 0));
        return group;
    }

    /** Sets the callback invoked when the toolbar's Project Settings button is clicked. */
    public void setOnProjectSettings(Runnable callback) {
        this.onProjectSettings = callback;
    }

    /** Sets the callback invoked when the toolbar's Overlay Editor button is clicked. */
    public void setOnOverlayEditor(Runnable callback) {
        this.onOverlayEditor = callback;
    }

    /**
     * Wires the in-place rebuild a tab pick needs ({@link #selectTab}). Read at click time, so it may be wired
     * at any point before a tab can be clicked.
     */
    public void setOnRebuildBar(Runnable callback) {
        this.onRebuildBar = callback;
    }

    /** Sets the callback invoked when the toolbar's Parameters button is clicked. */
    public void setOnParameters(Runnable callback) {
        this.onParameters = callback;
    }

    /**
     * Wires the reader-mode toggle. Reader mode renders the bot the way somebody who only wants to <em>run</em>
     * it sees it — full colour, no controls — and until now an author could not get to it at all: the only
     * switch was the banner reader mode itself draws, which is to say it was reachable only from a project that
     * had already been installed from the gallery.
     *
     * @param initial whether the project is in reader mode already (an installed bot opens that way)
     */
    /** @see MenuBarManager#setOnPreviewAsUser */
    public void setOnPreviewAsUser(Runnable onPreview) {
        this.onPreviewAsUser = onPreview;
    }

    /**
     * Creates the center: Studio's own row, and under it the plugin row — a tab per plugin that has bar
     * buttons, then the selected plugin's buttons (2026-10-28, {@link PluginTabs}). Each row is an
     * {@link OverflowBar}, so what does not fit goes into a {@code »} menu rather than onto more rows.
     *
     * <p>Until 2026-10-28 this was one bar of every button, Studio's and each plugin's, in sections sorted by
     * shared toolbar groups. With the SDK alone it overflowed, the groups said nothing on the bar, and nothing
     * separated the project's buttons from the plugins'.
     */
    public Node createCentre() {
        // CALLED MORE THAN ONCE. Since 2026-09-05 UIManager rebuilds the centre on LibrariesChangedEvent,
        // because it is the only plugin-facing surface composed once rather than on each open — the menus
        // and the pickers ask PluginHost when they are opened, so they were always live, and this bar was
        // why installing a plugin needed a restart before its buttons appeared. Picking a tab rebuilds it too.
        // So nothing here may consume state that only exists the first time: every callback is a field set by
        // StudioActions on this same instance.
        List<Placed> placed = new ArrayList<>();
        ActionContext ctx = actionContext();

        List<Node> own = new ArrayList<>();
        for (ToolbarItem item : studioItems()) own.add(place(placed, ctx, item));
        OverflowBar studioRow = new OverflowBar(5, 5, 1, HPos.CENTER, own.toArray(Node[]::new));

        List<PluginBar> bars = PluginTabs.ordered(PluginHost.barItemsByPlugin(), tabs());
        Node centre = studioRow;
        if (!bars.isEmpty()) {
            List<Node> buttons = new ArrayList<>();
            for (ToolbarItem item : bars.getFirst().items()) buttons.add(place(placed, ctx, item));
            OverflowBar pluginButtons = new OverflowBar(5, 5, TOOLBAR_MAX_ROWS, HPos.LEFT,
                    buttons.toArray(Node[]::new));
            PluginTabs pluginRow = new PluginTabs(bars, pluginButtons, plugin -> selectTab(bars, plugin));
            VBox rows = new VBox(6, studioRow, pluginRow);
            rows.setMinWidth(0);
            centre = rows;
        }
        this.items = List.copyOf(placed);
        return centre;
    }

    /**
     * Studio's own buttons, spelled the way a plugin would have to spell them. Going through the same record
     * and the same builder is the point rather than a tidiness: an item built by a second path is an item that
     * drifts, and the first thing anybody would notice is that the host's buttons are the ones that line up.
     */
    private List<ToolbarItem> studioItems() {
        List<ToolbarItem> own = new ArrayList<>();

        // 🧭 Setup stood first in this group until 2026-08-31 and is the SDK plugin's 📋 Project Setup item
        // now, placed by the same merge as the pilot's and the capture items'. Every row of that checklist
        // reads a file the plugin owns, so there is nothing left for the shell to wire.

        own.add(ToolbarItem.id("settings").label("⚙ Settings")
                .tooltip("Project settings: the standard resolution templates are authored at, and favourite methods")
                .onBar(10)
                .onPress(() -> c -> run(onProjectSettings)));

        // 🎯 Capture Targets stood here at PROJECT/30 until 2026-08-31 and is the SDK plugin's item now,
        // placed by the same merge as any other plugin's. The list it manages is capture.json, which is the
        // plugin's file — so the shell no longer reads it and can no longer put the current default's name on
        // the button. That label is what the move cost: toolbarItems() is called with no StudioServices, so a
        // plugin's item has no project to name.

        // 🔀 Flow stood here at AUTHORING/10 until 2026-09-11 and is the SDK plugin's 🔀 Activity Flow item
        // now, in the same slot, placed by the same merge as any other plugin's. It went because a flow is
        // the one thing on this bar that does not reduce to data the contract already carries: a parameter
        // is a ParameterRow, and the window over those stayed here.

        own.add(ToolbarItem.id("parameters").label("🎚 Parameters")
                .tooltip("The project's variables: every value the bot reads, with its tag and its editor")
                .onBar(20)
                .onPress(() -> c -> run(onParameters)));

        // 🎮 Pilot stood here until 2026-08-30 and is the SDK plugin's item now, placed by the same merge as
        // any other plugin's. It is the case this surface was added for: a whole feature behind one button,
        // where the host owns the bar and the plugin owns everything the press opens.

        // 🐞 Debug output stood here at RUN/20 until 2026-09-27, toggling the `debug` key of
        // botmaker-project.properties, and came back on 2026-09-29 as the run property Runs.DEBUG. What
        // a bot does by default is still its own @SdkValue(SdkValue.Id.SETTINGS) value; whether this machine's runs
        // print and trace is a fact about running here, so it is a run property, and the host's to toggle
        // (docs/refactor/40-run-trace.md).
        own.add(ToolbarItem.id("debug-output").label(() -> debugOutput().label())
                .tooltip("Whether runs on this computer print and trace their debug lines: the bot's own "
                        + "setting, always on, or always off. Takes effect on the next run.")
                .onBar(30)
                .onPress(() -> c -> cycleDebugOutput()));

        // The same action as View ▸ Preview as user, not a second thing that sounds like it. This used to be
        // a "Reader mode" toggle that hid the editor's controls in place — a third rendering of the project
        // that answered "what does a user see?" with something no user ever sees. The Runner is the answer.
        own.add(ToolbarItem.id("preview").label("👁 Preview")
                .tooltip("Open this bot the way someone who only runs it sees it — the Runner window, with the "
                        + "switches and values you chose to expose. Its header brings you back.")
                .onBar(40)
                .onPress(() -> c -> run(onPreviewAsUser)));

        // 🖱 Input stood here at TOOLS/10 until 2026-09-27, opening Studio's Input & Clicks window over
        // botmaker-project.properties. The action moved and the sentence moved with it, as this comment said
        // it would: the settings are the SDK's @SdkValue(SdkValue.Id.SETTINGS) value, and ⚙ Bot Settings is that
        // plugin's.

        // ✂ Templates stood here at TOOLS/20 until 2026-08-31 and is the SDK plugin's ✂ Capture Templates
        // now, merged into this same slot. It is the second whole feature to leave through this surface, and
        // the clearest case for it: the host owns the bar, the plugin owns the capture target it reads, the
        // pixels it grabs and the picture folder it writes.

        own.add(ToolbarItem.id("overlay").label("⧉ Overlay")
                .tooltip("Build the bot over the running game: a compact block tree on top of the target window")
                .onBar(50)
                .onPress(() -> c -> run(onOverlayEditor)));

        // ⏺ Record stood here at order 40 and opened the overlay straight into recording. It is the SDK
        // plugin's ⏺ Record Macro since 2026-09-02 and arrives as a ToolbarItem like any other plugin's:
        // deciding that a recorded click is a Mouse.click is that plugin's vocabulary, not the shell's.

        // 🗂 Resources stood here at TOOLS/50 until 2026-09-05 and is DELETED. The window it opened is the
        // SDK plugin's 🖼 Manage Pictures (TOOLS/30) since 2026-09-01, when the last thing here that knew
        // what a picture is called went with it — and setOnAccessResources lost its caller on the same day,
        // so for four days the button ran a null Runnable and did nothing at all. That is worse than an
        // absent button: it teaches the user the feature is broken rather than that it moved. The slot is
        // deliberately left empty rather than backfilled — the shell has no handle on another plugin's
        // toolbar item, the same reason the Capture Templates and Capture Targets steps lost their buttons.

        // 📐 The resolution read-out stood at the end of this bar until 2026-10-28, the primary screen's size.
        // It is gone, not moved: the user's call, a screen size is the capture plugin's concern, not Studio's.
        return List.copyOf(own);
    }

    /**
     * The plugin tabs, most recent first: the project's, or this window's own while no project is open.
     */
    private List<String> tabs() {
        return settings == null || settings.current() == null ? sessionTabs : settings.current().toolbarTabs();
    }

    /**
     * Moves {@code plugin}'s tab to the front, which selects it, saves that, and rebuilds the bar.
     *
     * <p>The rebuild is asked for rather than done here: this class builds the bar and does not own where it
     * hangs. {@link UIManager} wires {@link #setOnRebuildBar} to the same {@code setCenter} it uses when a
     * project's plugins change, which is the one path that swaps a live node into the scene graph.
     */
    private void selectTab(List<PluginBar> ordered, PluginCoordinate plugin) {
        List<String> next = PluginTabs.picked(ordered, plugin);
        if (settings == null || settings.current() == null) sessionTabs = next;
        else settings.update(settings.current().withToolbarTabs(next));
        run(onRebuildBar);
    }

    /** One button on the bar and the item it was built from, so {@link #refreshItems()} can re-read it. */
    private record Placed(ToolbarItem item, Button button) {}

    /** What is currently on the bar, in draw order, so {@link #refreshItems()} can re-read the suppliers. */
    private List<Placed> items = List.of();

    /** Builds {@code item}'s button and records it for {@link #refreshItems()}. */
    private Button place(List<Placed> into, ActionContext ctx, ToolbarItem item) {
        Button button = ToolbarItems.button(item, ctx);
        into.add(new Placed(item, button));
        return button;
    }

    /** This checkout's debug output choice, read from its run properties at every refresh. */
    private DebugOutput debugOutput() {
        if (settings == null || settings.current() == null) return DebugOutput.BOT;
        return DebugOutput.fromProperty(settings.current().runProperties().get(Runs.DEBUG.name()));
    }

    /** Moves to the next choice; the settings write publishes the change that relabels the button. */
    private void cycleDebugOutput() {
        if (settings == null || settings.current() == null) return;
        settings.update(settings.current()
                .withRunProperty(Runs.DEBUG.name(), debugOutput().next().propertyValue()));
    }

    /** Runs a wired callback, or does nothing when the bar was built before that callback was set. */
    private static void run(Runnable callback) {
        if (callback != null) callback.run();
    }

    /**
     * Re-reads every item's label and icon.
     *
     * <p>Cheap by contract — a supplier is a field read and a format — so this runs on the events that
     * already redraw parts of the bar rather than on a timer.
     */
    private void refreshItems() {
        for (Placed p : items) ToolbarItems.refresh(p.button(), p.item());
    }

    /**
     * The facts a toolbar click is handed.
     *
     * <p>Built once and reused: {@link HostActionContext} reads everything at call time, so one context is
     * correct for every project opened after it — which a captured one would not be, and a button outlives
     * the project it was built under.
     */
    private ActionContext actionContext() {
        return new HostActionContext(() -> settings == null ? null : settings.projectConfig());
    }

    /**
     * Creates the right-side group: Compile, Run, Debug, Follow, Stop, Step, Continue — the whole "make this bot
     * go" sequence in the order you'd use it.
     *
     * <p>An {@link OverflowBar}, like the rows of {@link #createCentre()}, and for the same reason turned inside out:
     * as an {@code HBox} this cluster held one line at any width, so a narrow window took the space out of the
     * <em>centre</em> group instead, which then wrapped onto three or four rows and pushed the bar's height up.
     * Wrapping here lets the two groups share the shortfall a row at a time.
     *
     * <p>What makes the wrap actually happen is the preferred width bound to a share of the toolbar in
     * {@code UIManager.createScene()} — a {@code BorderPane} hands its right child that child's <em>preferred</em>
     * width, so a group left to compute its own would report one row's worth and never be squeezed.
     */
    public OverflowBar createExecutionGroup() {
        Button compileButton = new Button("⚙ Compile");
        compileButton.getStyleClass().add("toolbar-btn");
        compileButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.CompilationRequestedEvent()));

        runButton = new Button("▶ Run");
        runButton.getStyleClass().addAll("toolbar-btn", "btn-run");
        runButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent()));

        debugButton = new Button("🐞 Debug");
        debugButton.getStyleClass().addAll("toolbar-btn", "btn-debug");
        debugButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugStartRequestedEvent()));

        followButton = new Button("👁 Follow");
        followButton.getStyleClass().addAll("toolbar-btn", "btn-follow");
        followButton.setTooltip(new Tooltip("Run and highlight each executing block live (never pauses at breakpoints)"));
        followButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.FollowStartRequestedEvent()));

        unifiedStopButton = new Button("⏹ Stop");
        unifiedStopButton.getStyleClass().addAll("toolbar-btn", "btn-stop");
        unifiedStopButton.setDisable(true);
        unifiedStopButton.setOnAction(e -> {
            if (currentAppState == AppState.RUNNING) eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
            else if (currentAppState == AppState.DEBUGGING) eventBus.publish(new CoreApplicationEvents.DebugStopRequestedEvent());
        });

        // These two carry .toolbar-btn like the other five. Without it they fell back to Modena's own button,
        // which in a dark theme is a shape with no fill — the "Compile/Follow group renders as a bare line".
        stepOverButton = new Button("⤵ Step");
        stepOverButton.getStyleClass().add("toolbar-btn");
        stepOverButton.setDisable(true);
        stepOverButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugStepOverRequestedEvent()));

        continueButton = new Button("⏩ Cont");
        continueButton.getStyleClass().add("toolbar-btn");
        continueButton.setDisable(true);
        continueButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugContinueRequestedEvent()));

        return new OverflowBar(5, 5, TOOLBAR_MAX_ROWS, HPos.RIGHT,
                compileButton, runButton, debugButton, followButton, unifiedStopButton, stepOverButton, continueButton);
    }

    private void setAppState(AppState state) {
        this.currentAppState = state;
        updateToolbarState();
    }

    private void updateToolbarState() {
        boolean isBusy = (currentAppState != AppState.IDLE);
        runButton.setDisable(isBusy);
        debugButton.setDisable(isBusy);
        followButton.setDisable(isBusy);
        unifiedStopButton.setDisable(!isBusy);

        if (currentAppState == AppState.DEBUGGING) {
            unifiedStopButton.setStyle("-fx-background-color: #E74C3C; -fx-text-fill: white;");
        } else if (currentAppState == AppState.RUNNING) {
            unifiedStopButton.setStyle("-fx-background-color: #E74C3C; -fx-text-fill: white;");
            stepOverButton.setDisable(true);
            continueButton.setDisable(true);
        } else {
            unifiedStopButton.setStyle("");
            stepOverButton.setDisable(true);
            continueButton.setDisable(true);
        }
    }

    private void updateDebugControls(boolean isPaused) {
        if (currentAppState == AppState.DEBUGGING) {
            stepOverButton.setDisable(!isPaused);
            continueButton.setDisable(!isPaused);
        }
    }
}
