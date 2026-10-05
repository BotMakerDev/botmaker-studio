package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.overlay.OverlayContext;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.overlay.Watched;
import com.botmaker.plugin.api.toolbar.ActionContext;
import com.botmaker.plugin.api.toolbar.Pressed;
import com.botmaker.plugin.api.toolbar.ToolbarGroup;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents.CodeUpdatedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ExecutionRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.StatusMessageEvent;
import com.botmaker.studio.events.CoreApplicationEvents.UIBlocksUpdatedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.plugin.HostOverlayContext;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.capture.ScreenOverlay;
import com.botmaker.studio.services.capture.TargetCapture;
import com.botmaker.studio.services.overlay.OverlayCalls;
import com.botmaker.studio.services.overlay.OverlayTargets;
import com.botmaker.studio.services.overlay.WatchedScreen;
import com.botmaker.studio.ui.app.ToolbarVisibility;
import com.botmaker.studio.ui.app.run.RunBarDock;
import com.botmaker.studio.ui.render.menu.StatementMenu;
import com.botmaker.studio.util.MethodSignature;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Statement;

import java.lang.reflect.Executable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The <b>overlay editor</b>: a panel docked beside the screen the bot watches, where the bot is built while
 * looking at what it sees. This class is the coordinator — opening, the event subscriptions, the caret, every
 * edit, and the FX-thread pending state that sequences an edit against the re-parse it causes
 * ({@link #pendingInsert}, {@link #pendingOverload}, {@link #pendingConfig}).
 *
 * <p>Top to bottom the panel is: the {@link PanelHeader} (what it is beside, ⇄ Change, the run), the
 * {@link TargetStrip} (where blocks go), the caret bar and the script ({@link OverlayTreeView} over
 * {@link BlockTree}), a status line, and the {@link ToolTabs}. The window is a {@link DockedPanel}. None of
 * them holds a reference back to this class; each takes callbacks.
 *
 * <p><b>What it is beside.</b> A live private session first, as before; then what a plugin says the bot watches
 * ({@code OverlayPart.watched}, the SDK's {@code Sdk.captureSource()}); only when no plugin says, a picker of
 * the windows that are open. Asked again on every code update, so changing the capture source in the plugin's
 * own picker (⇄ Change) moves the panel beside the new window.
 *
 * <p><b>Where blocks go.</b> The plugins' targets ({@link OverlayTargets}): picking a chip opens that method's
 * file in the main editor — visibly, the status line says so — and parks the caret inside it. In a game bot
 * every file that opens by default is generated and read-only, which is why the panel names its target at all.
 *
 * <p>Replaced {@code ProgramShapeOverlay} on 2026-10-06, a 340px floating HUD over the window with an
 * Activity combo, a Method combo, two ▲▼ sets that did different things, and ⏺ Record, which went with it.
 * See {@code docs/refactor/42-overlay-editor.md}.
 */
public final class OverlayEditor {

    /** Single live instance — pressing the toolbar button again focuses it instead of opening another. */
    private static OverlayEditor active;

    private final CodeEditorService context;
    private final ProjectState state;
    private final ProjectSettingsService settings;
    private final ScreenCaptureService capture;
    private final StudioServices services;
    /** What the panel is docked beside; replaced by ⇄ Change or by the plugin's answer changing. */
    private volatile WatchedScreen watched;

    private DockedPanel panel;
    private PanelHeader header;
    private final TargetStrip strip;
    private final OverlayTreeView tree;
    private ToolTabs tools;
    private final OverlayPalette palette;
    private final ArgumentConfigPopover config;
    private final Label status = new Label();
    private final CheckBox autoFillArgs = new CheckBox("Fill arguments after adding");
    /** Each plugin's tool context for this opening, by plugin id; closed with the panel. */
    private final Map<String, ToolContext> toolContexts = new HashMap<>();

    private CodeBlock root;
    /** {@link #root}'s one-walk structural index, rebuilt lazily by {@link #index()} when the tree changes. */
    private CodeBlock indexedRoot;
    private BlockTree.Index index;

    /** Label of the method the script shows, or {@code null} for every top-level body. */
    private String selectedMethod;
    /** The method a picked target names ({@code body}), scoped to first when its file opens. */
    private String preferredMethod;
    /** Whether the first targets answer has been used to pick where blocks go. */
    private boolean targetChosen;

    /** A specific overload requested from the palette bar, applied once the inserted call is re-parsed. */
    private MethodSignature pendingOverload;

    /** Unsubscribes the capture-overlay visibility listener when the panel closes. */
    private AutoCloseable captureVisibility;
    /** Event-bus subscriptions, dropped on close so a reopen doesn't stack another set. */
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    /** While true, a {@code stage.hide()} is a temporary capture-hide, not a real close — skip teardown. */
    private boolean suppressHideTeardown;
    /** Whether {@link #hideForCapture} is the reason the panel is off screen — the only case it may re-show it. */
    private boolean hiddenForCapture;
    /** The last status message published, as it was published; what a refused tool insert is told. */
    private volatile String lastMessage;
    /** Whether a {@link #rewatch} is already asking, so code updates in a burst ask once. */
    private boolean rewatching;

    /** What an edit just requested does once the re-parse lands: which kind of edit, so what to say and open. */
    private enum Landed {
        /** A block from the palette: its argument popover opens when "Fill arguments" is on. */
        ADDED,
        /** A call a tool inserted, already filled. */
        PLACED,
        /** A block moved by Alt+↑/↓ or ⋮. */
        MOVED
    }

    /**
     * A just-requested edit whose result must be focused after the next {@link UIBlocksUpdatedEvent}.
     *
     * <p><b>Block reuse does not make this redundant</b> (2026-09-15): what this focuses was just inserted, so it
     * did not exist before the re-parse and cannot have survived it. See {@code docs/refactor/30-block-reuse.md}
     * §9.
     *
     * @param at   where the block landed, as a position: the block is replaced by the re-parse
     * @param kind what kind of edit it was
     */
    private record PendingFocus(BlockTree.Position at, Landed kind) {}

    private PendingFocus pendingInsert;

    /** The statements whose branches are folded shut, by position, which survives a re-parse. */
    private final Set<BlockTree.Position> collapsed = new HashSet<>();

    /**
     * A block whose config popover should open on the <em>next</em> re-parse: applying a palette-picked
     * overload ({@link #pendingOverload}) is itself an edit, so the block from {@link #pendingInsert} is
     * replaced again before the user could touch it.
     */
    private BlockTree.Position pendingConfig;

    private OverlayEditor(CodeEditorService context, ProjectSettingsService settings, ScreenCaptureService capture,
                          WatchedScreen watched) {
        this.context = context;
        this.state = context.getState();
        this.settings = settings;
        this.capture = capture;
        this.watched = watched;
        this.services = HostServices.forProject(context.getConfig(), this::stage);
        this.strip = new TargetStrip(this::pickTarget, this::pickMethod);
        this.palette = new OverlayPalette(context, settings,
                new OverlayPalette.Callbacks(this::insertLibraryCall, this::addBelow));
        this.config = new ArgumentConfigPopover(context, this::index, this::stage);
        // After `config`: the row's ⚙ opens the popover, and a field initializer could not see it yet.
        this.tree = new OverlayTreeView(context, new OverlayTreeView.Callbacks(this::move, this::delete,
                config::open, this::moveStatement, this::toggleFold));
        this.tree.setDiagnostics(context.getDiagnosticsManager());
        status.getStyleClass().add("overlay-status");
        status.setWrapText(true);
        autoFillArgs.setSelected(true);
        autoFillArgs.setStyle(OverlayStyles.LABEL);
        autoFillArgs.setTooltip(new Tooltip("When on, adding a call opens its argument editor"));
    }

    // ── opening ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens (or focuses) the overlay editor. FX thread.
     *
     * @param sessionWindow the live private session's host window id, or {@code 0} for none; it outranks every
     *                      other answer, because while a session is up that is where the game is
     */
    public static void open(Window owner, CodeEditorService context, ProjectSettingsService settings,
                            ScreenCaptureService capture, LongSupplier sessionWindow) {
        // An editor still opening — its window read off the FX thread — counts as open, or a second press in
        // that moment builds a second panel.
        if (active != null) {
            if (active.stage() != null && active.stage().isShowing()) active.stage().toFront();
            return;
        }
        WatchedScreen watched = watchedNow(context, owner, sessionWindow);
        if (watched == null) watched = pickWindow(owner);
        if (watched == null) {
            OverlayStyles.warn(owner, "The overlay editor docks beside the window the bot watches, and none is "
                    + "open.\n\nOpen the app or game you're automating — start it with \"Launch Target\" if the "
                    + "bot launches it — and try again.");
            return;
        }
        OverlayEditor editor = new OverlayEditor(context, settings, capture, watched);
        active = editor;
        editor.start(owner);
    }

    /** The live session, else what the first plugin that says names; null when neither answers. */
    private static WatchedScreen watchedNow(CodeEditorService context, Window owner, LongSupplier sessionWindow) {
        long id = sessionWindow == null ? 0 : sessionWindow.getAsLong();
        if (id != 0) return WatchedScreen.session(id);
        return firstOpen(pluginsWatched(context, owner), sessionWindow);
    }

    /** What each plugin says the bot watches, in plugin order. FX thread: a plugin reads its own values. */
    private static List<Watched> pluginsWatched(CodeEditorService context, Window owner) {
        StudioServices services = HostServices.forProject(context.getConfig(), () -> owner);
        OverlayContext ask = () -> services;
        List<Watched> said = new ArrayList<>();
        for (PluginHost.OwnedOverlay owned : PluginHost.overlayParts()) {
            try {
                owned.part().watchedFor(ask).ifPresent(said::add);
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Warning: " + owned.pluginId() + " could not say what the bot watches: " + e);
            }
        }
        return said;
    }

    /** The first of {@code said} that names something open now; null for none. Reads the window list. */
    private static WatchedScreen firstOpen(List<Watched> said, LongSupplier sessionWindow) {
        for (Watched w : said) {
            Optional<WatchedScreen> screen = WatchedScreen.resolve(w, sessionWindow);
            if (screen.isPresent()) return screen.get();
        }
        return null;
    }

    /** Asks which open window to dock beside; null when cancelled or none is open. */
    private static WatchedScreen pickWindow(Window owner) {
        String title = OverlayWindowPicker.ask(owner,
                OverlayWindowPicker.candidates(ScreenCaptureService.listWindowTitles()));
        return title == null ? null : WatchedScreen.window(new TargetCapture.WindowRef(title), "\"" + title + "\"");
    }

    /** Off the FX thread: raise the watched window, read where it is, then show the panel beside it. */
    private void start(Window owner) {
        WatchedScreen screen = watched;
        Thread.ofVirtual().name("overlay-editor-open").start(() -> {
            screen.windowRef().ifPresent(capture::raiseWindow);
            java.awt.Rectangle bounds = screen.bounds();
            Platform.runLater(() -> {
                if (bounds == null) {
                    if (active == this) active = null;
                    OverlayStyles.warn(owner, "Couldn't find " + screen.label() + ". Is it open?");
                    return;
                }
                show(bounds);
            });
        });
    }

    private void show(java.awt.Rectangle bounds) {
        header = new PanelHeader(new PanelHeader.Callbacks(this::changeWatched, () -> panel.dock(),
                () -> context.getEventBus().publish(new ExecutionRequestedEvent()), () -> panel.close()));
        header.showWatched(watched.label(), bounds);
        tools = new ToolTabs(this::toolContext, this::actionsRow, new VBox(8, palette.node(), autoFillArgs));

        VBox where = new VBox(4, strip.node());
        where.setPadding(new Insets(8));
        where.setStyle(OverlayStyles.PANEL);
        VBox script = new VBox(4, caretBar(), tree.node(), status);
        script.setPadding(new Insets(8));
        script.setStyle(OverlayStyles.PANEL);
        VBox.setVgrow(script, Priority.ALWAYS);
        VBox toolsPanel = new VBox(tools.node());
        toolsPanel.setStyle(OverlayStyles.PANEL);
        VBox content = new VBox(6, header.node(), where, script, toolsPanel);
        content.setPadding(new Insets(6));
        content.setStyle("-fx-background-color: transparent;");

        StudioProjectSettings.OverlayState saved = settings.current().overlayState();
        double width = saved != null && saved.width() > 0 ? saved.width() : DockedPanel.DEFAULT_WIDTH;
        panel = new DockedPanel(content, header.handle(), width, () -> watched.bounds(),
                b -> header.showWatched(watched.label(), b), docked -> header.showDock(!docked),
                () -> !config.isOpen());
        Stage stage = panel.stage();
        stage.setOnHidden(e -> {
            if (suppressHideTeardown) return;   // a temporary capture-hide, not a real close
            teardown();
        });
        installKeys();
        // The run bar is drawn in the header while the panel is open, not in a window of its own.
        RunBarDock.offer(header.runSlot());
        panel.show(bounds);

        // Hide the panel (and any open config popover) while a capture draw surface is up, so it doesn't sit
        // over the selection — restored when the surface closes.
        captureVisibility = ScreenCaptureService.addCaptureOverlayListener(new ScreenOverlay.CaptureOverlayListener() {
            @Override public void onShown() { hideForCapture(true); }
            @Override public void onHidden() { hideForCapture(false); }
        });
        subscriptions.add(context.getEventBus().subscribe(UIBlocksUpdatedEvent.class, e -> {
            if (stage.isShowing()) {
                root = e.rootBlock();
                Platform.runLater(this::onBlocksUpdated);
            }
        }));
        // A code update can add or remove a target, change what a plugin offers, or change what the bot
        // watches (a plugin's picker writes the bot's Java): all three are asked again.
        subscriptions.add(context.getEventBus().subscribe(CodeUpdatedEvent.class, e -> {
            if (stage.isShowing()) Platform.runLater(this::onCodeUpdated);
        }));
        // Refusals raised inside CodeEditor are published, not thrown, and the main editor's status bar is not
        // on screen while the panel is.
        subscriptions.add(context.getEventBus().subscribe(StatusMessageEvent.class, e -> {
            // Kept as published, not only once the status line catches up: a refused insert reports the
            // reason to its tool before the deferred update below has run.
            lastMessage = e.message();
            if (stage.isShowing()) Platform.runLater(() -> status(e.message()));
        }));

        root = context.getRootBlock().orElse(null);
        refreshMethods();
        ensureCursor();
        render();
        refreshTargets();
    }

    private void teardown() {
        settings.update(settings.current().withOverlayState(
                new StudioProjectSettings.OverlayState((int) panel.width())));
        RunBarDock.withdraw(header.runSlot());
        toolContexts.values().forEach(ToolContext::close);
        toolContexts.clear();
        if (captureVisibility != null) {
            try { captureVisibility.close(); } catch (Exception ignored) {}
            captureVisibility = null;
        }
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
        config.close();
        if (active == this) active = null;
    }

    /**
     * Keyboard: ↑/↓ move the caret, → step into (Shift+→ the next branch), ← step out, Alt+↑/↓ move the block,
     * Enter configure, Delete/Backspace remove. Never while a text field has focus.
     */
    private void installKeys() {
        var scene = panel.scene();
        scene.setOnKeyPressed(e -> {
            if (scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) return;
            switch (e.getCode()) {
                case RIGHT -> move(e.isShiftDown()
                        ? CursorNavigator.stepIntoNext(cursor(), root)
                        : CursorNavigator.stepInto(cursor()));
                case LEFT -> move(CursorNavigator.stepOut(cursor(), root));
                case UP -> { if (e.isAltDown()) moveFocused(-1); else move(CursorNavigator.stepBack(cursor())); }
                case DOWN -> { if (e.isAltDown()) moveFocused(+1); else move(CursorNavigator.stepOver(cursor())); }
                case ENTER -> {
                    if (focusedStatement() instanceof MethodInvocationBlock mib) config.open(mib);
                }
                case DELETE, BACK_SPACE -> deleteFocused();
                default -> { return; }
            }
            e.consume();
        });
    }

    /** The caret: ▲▼ move it, ⤵ ⤴ in and out of a block, ⇄ to the next branch, ⟳ redraws. */
    private HBox caretBar() {
        Button up = OverlayStyles.iconButton("▲", "Caret up (↑)", () -> move(CursorNavigator.stepBack(cursor())));
        Button down = OverlayStyles.iconButton("▼", "Caret down (↓)", () -> move(CursorNavigator.stepOver(cursor())));
        Button into = OverlayStyles.iconButton("⤵", "Into the block (→)", () -> move(CursorNavigator.stepInto(cursor())));
        Button branch = OverlayStyles.iconButton("⇄", "Next branch — else / case / otherwise (Shift+→)",
                () -> move(CursorNavigator.stepIntoNext(cursor(), root)));
        Button out = OverlayStyles.iconButton("⤴", "Out of the block (←)", () -> move(CursorNavigator.stepOut(cursor(), root)));
        Button refresh = OverlayStyles.iconButton("⟳", "Redraw", this::render);
        HBox bar = new HBox(4, OverlayStyles.dimLabel("Caret"), up, down, into, branch, out, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Stage stage() {
        return panel == null ? null : panel.stage();
    }

    /** Sets the panel's one-line readout: what the last action did, or why it did nothing. */
    private void status(String message) {
        status.setText(message == null ? "" : message);
    }

    // ── what it is beside ───────────────────────────────────────────────────────────────────────────────

    /**
     * ⇄ Change: the first plugin's own picker for what the bot watches, which writes the bot's Java and so
     * moves the panel on the code update; with none, the picker of open windows.
     */
    private void changeWatched() {
        for (PluginHost.OwnedOverlay owned : PluginHost.overlayParts()) {
            Optional<Pressed> change = owned.part().changeWatched();
            if (change.isEmpty()) continue;
            try {
                change.get().get().accept(itemContext());
            } catch (RuntimeException | LinkageError e) {
                status(owned.pluginName() + " could not open its picker: " + e.getMessage());
            }
            return;
        }
        WatchedScreen picked = pickWindow(stage());
        if (picked != null) setWatched(picked);
    }

    /** Docks beside {@code next}, read off the FX thread. */
    private void setWatched(WatchedScreen next) {
        watched = next;
        Thread.ofVirtual().start(() -> {
            java.awt.Rectangle bounds = next.bounds();
            Platform.runLater(() -> {
                header.showWatched(next.label(), bounds);
                if (bounds != null) panel.dockBeside(bounds);
                status("Docked beside " + next.label() + ".");
            });
        });
    }

    /**
     * Asks the plugins again what the bot watches, and moves beside it when that changed. The plugins are
     * asked here; what they name is looked up among the open windows off the FX thread. A panel beside the
     * live session stays there, and the session is not asked again: revealing its window has a side effect.
     */
    private void rewatch() {
        if (rewatching || watched.windowRef().map(r -> r.windowId() != null).orElse(false)) return;
        List<Watched> said = pluginsWatched(context, stage());
        if (said.isEmpty()) return;
        rewatching = true;
        Thread.ofVirtual().name("overlay-rewatch").start(() -> {
            WatchedScreen now = firstOpen(said, () -> 0);
            Platform.runLater(() -> {
                rewatching = false;
                if (now != null && !now.sameAs(watched) && stage() != null && stage().isShowing()) setWatched(now);
            });
        });
    }

    // ── where blocks go ─────────────────────────────────────────────────────────────────────────────────

    private void onCodeUpdated() {
        refreshTargets();
        if (tools != null) tools.refreshActions();
        rewatch();
    }

    /**
     * Asks the plugins' target types of the bot, off the FX thread: the sources are read here, the parse there.
     * The first answer picks where blocks go — the target last edited, else the first.
     */
    private void refreshTargets() {
        List<OverlayPart.TargetType> types = new ArrayList<>();
        for (PluginHost.OwnedOverlay owned : PluginHost.overlayParts()) types.addAll(owned.part().targets());
        if (types.isEmpty()) {
            strip.showTargets(List.of(), null);
            targetChosen = true;
            return;
        }
        Supplier<BotIndex> index = BotIndex.prepare(context.getConfig(), state);
        Thread.ofVirtual().name("overlay-targets").start(() -> {
            List<OverlayTargets.Target> found;
            try {
                found = OverlayTargets.find(index.get(), types);
            } catch (RuntimeException e) {
                System.err.println("Overlay targets could not be read: " + e);
                found = List.of();
            }
            List<OverlayTargets.Target> targets = found;
            Platform.runLater(() -> {
                if (stage() == null || !stage().isShowing()) return;
                strip.showTargets(targets, currentTargetKey());
                if (!targetChosen) {
                    targetChosen = true;
                    initialTarget(targets).ifPresent(this::pickTarget);
                }
            });
        });
    }

    private Optional<OverlayTargets.Target> initialTarget(List<OverlayTargets.Target> targets) {
        String last = settings.current().lastTarget();
        return targets.stream().filter(t -> t.key().equals(last)).findFirst()
                .or(() -> targets.stream().findFirst());
    }

    /** Opens {@code target}'s file in the main editor — visibly — and parks the caret inside its method. */
    private void pickTarget(OverlayTargets.Target target) {
        preferredMethod = target.method();
        selectedMethod = null;
        context.switchToFile(target.file());
        root = context.getRootBlock().orElse(null);
        refreshMethods();
        render();
        settings.update(settings.current().withLastTarget(target.key()));
        status("Editing " + target.label() + "." + target.method() + "() — " + target.file().getFileName()
                + " is open in the editor.");
    }

    /** Scopes the script to a method the user picked from the breadcrumb, parking the caret inside it. */
    private void pickMethod(String label) {
        selectedMethod = label;
        InsertionCursor c = index().methodCursor(label);
        if (c != null) state.setInsertionCursor(c);
        showWhere();
        render();
    }

    /**
     * Re-reads the open file's methods, keeping a still-valid selection; else the method a picked target
     * names, else {@code run}, else the first. Re-homes the caret when the selection changed.
     */
    private void refreshMethods() {
        List<String> labels = root == null ? List.of() : index().methodLabels();
        if (selectedMethod == null || !labels.contains(selectedMethod)) {
            selectedMethod = labels.stream()
                    .filter(l -> preferredMethod != null && l.startsWith(preferredMethod + "(")).findFirst()
                    .or(() -> labels.stream().filter(l -> l.startsWith("run(")).findFirst())
                    .orElse(labels.isEmpty() ? null : labels.getFirst());
            InsertionCursor c = selectedMethod == null ? null : index().methodCursor(selectedMethod);
            if (c != null) state.setInsertionCursor(c);
        }
        showWhere();
    }

    /** The breadcrumb, and which chip is lit: the target whose file is open and whose method is shown. */
    private void showWhere() {
        Path file = activePath();
        String name = file == null ? null : file.getFileName().toString().replaceFirst("\\.java$", "");
        strip.showMethod(name, selectedMethod, root == null ? List.of() : index().methodLabels());
        strip.select(currentTargetKey());
    }

    private String currentTargetKey() {
        Path file = activePath();
        if (file == null || selectedMethod == null) return null;
        return strip.targets().stream()
                .filter(t -> file.equals(t.file()) && selectedMethod.startsWith(t.method() + "("))
                .map(OverlayTargets.Target::key).findFirst().orElse(null);
    }

    private Path activePath() {
        ProjectFile file = state.getActiveFile();
        return file == null ? null : file.getPath();
    }

    // ── the tools ───────────────────────────────────────────────────────────────────────────────────────

    private ToolContext toolContext(String pluginId) {
        return toolContexts.computeIfAbsent(pluginId, id -> new ToolContext(services, () -> watched,
                this::insertCall, this::status));
    }

    /** The Actions tab: every {@code ToolbarGroup.OVERLAY} item, with the toolbar's show/hide menu. */
    private Node actionsRow() {
        Node row = OverlayItemRow.build(PluginHost.itemsIn(ToolbarGroup.OVERLAY), itemContext(), hiddenGroups());
        if (row == null) return null;
        // The panel has no menu bar, so the row carries the same menu the main toolbar does — otherwise a user
        // who hid a group from the bar could not get it back without leaving the game.
        row.setOnContextMenuRequested(e -> {
            ToolbarVisibility.menu(hiddenGroups(), next -> {
                settings.update(settings.current().withHiddenToolbarGroups(ToolbarVisibility.wire(next)));
                tools.refreshActions();
            }).show(row, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return row;
    }

    private Set<ToolbarGroup> hiddenGroups() {
        return settings == null ? EnumSet.noneOf(ToolbarGroup.class)
                : ToolbarVisibility.hidden(settings.current().hiddenToolbarGroups());
    }

    /**
     * What an action's click is handed: the title of the window the panel is beside, and where it is now. Read
     * through suppliers, since both move while the panel is up.
     */
    private ActionContext itemContext() {
        return new HostOverlayContext(context::getConfig,
                () -> watched.windowRef().map(TargetCapture.WindowRef::titleSubstring).orElse(null),
                () -> watched.area().orElse(null));
    }

    // ── insertion ───────────────────────────────────────────────────────────────────────────────────────

    /** The caret, when a block may be inserted below it; else null, with the status line saying why. */
    private InsertionCursor insertionPoint() {
        InsertionCursor c = cursor();
        if (c == null) {
            status("Nowhere to insert — click a row to place the caret first.");
            return null;
        }
        // The caret should never be parked in scaffolding (CursorNavigator skips read-only bodies), but the
        // panel reaches CodeEditor without going through a block, so don't rely on that alone.
        if (c.body().isReadOnly()) {
            status("Can't insert here — this is generated code. Pick a target above.");
            return null;
        }
        return c;
    }

    /** The slot just below {@code c}, armed to be focused once the re-parse lands. */
    private int armBelow(InsertionCursor c, Landed kind) {
        int insertIndex = Math.min(c.index() + 1, c.body().getStatements().size());
        pendingInsert = new PendingFocus(new BlockTree.Position(index().ordinalOf(c.body()), insertIndex), kind);
        return insertIndex;
    }

    /** A palette block below the caret. */
    private void insertBlock(BlockType type) {
        InsertionCursor c = insertionPoint();
        if (type == null || c == null) return;
        context.getCodeEditor().addStatement(c.body(), type, armBelow(c, Landed.ADDED));
    }

    /**
     * A tool's call below the caret, its values spelled by the grammar ({@link OverlayCalls}) — empty when
     * inserted, else the sentence why not.
     */
    private Optional<String> insertCall(Executable call, List<Object> arguments) {
        InsertionCursor c = insertionPoint();
        if (c == null) return Optional.of(status.getText());
        OverlayCalls.Statement statement;
        try {
            statement = OverlayCalls.call(call, arguments, PluginHost.grammar(), new ManagedConstants.Lookup(
                    ManagedConstants.scan(context.getConfig(), state), PluginHost.grammar()));
        } catch (IllegalArgumentException refused) {
            status(refused.getMessage());
            return Optional.of(refused.getMessage());
        }
        String before = activeText();
        lastMessage = null;
        context.getCodeEditor().insertStatement(c.body(), armBelow(c, Landed.PLACED), statement.node(),
                statement.imports());
        if (java.util.Objects.equals(before, activeText())) {
            pendingInsert = null;
            String why = lastMessage;
            return Optional.of("Studio refused to insert " + statement.source() + " here — "
                    + (why == null || why.isBlank() ? "it would not compile." : why));
        }
        return Optional.empty();
    }

    private String activeText() {
        ProjectFile file = state.getActiveFile();
        return file == null ? null : file.getContent();
    }

    /**
     * A fresh call of {@code facade.method} below the caret. When {@code overload} is given it is applied once
     * the re-parsed block is available ({@link #pendingOverload}); otherwise the fewest-argument overload (or
     * the project favourite).
     */
    private void insertLibraryCall(com.botmaker.plugin.api.catalog.FacadeEntry facade, String method,
                                   MethodSignature overload) {
        pendingOverload = overload;
        insertBlock(new BlockType.LibraryCall("OVL_" + facade.simpleName() + "_" + method, method,
                com.botmaker.studio.palette.BlockCategory.INPUT, facade.type(), method, List.of()));
    }

    private void addBelow(Node anchor) {
        InsertionCursor c = cursor();
        if (c == null) return;
        StatementMenu.create(context.getProjectAnalyzer(), c.body().getAstNode(), this::insertBlock)
                .show(anchor, Side.BOTTOM, 0, 0);
    }

    /** Removes the block the caret sits on (Delete/Backspace), leaving the caret on the slot above it. */
    private void deleteFocused() {
        InsertionCursor c = cursor();
        StatementBlock stmt = focusedStatement();
        if (c == null || stmt == null) {
            status("Nothing to delete — the caret is on an empty slot.");
            return;
        }
        delete(stmt, c.body(), c.index());
    }

    /**
     * Removes {@code stmt} through the same {@code CodeEditor.deleteStatement} the main editor's ✕ uses, so its
     * guards apply here too. The caret is re-homed onto the slot above first: the re-parse replaces every block,
     * and a caret left on the deleted index would point at whatever slid up into it. The target is the block's
     * {@link CodeBlock#enclosingStatement()}: a call row holds the {@code MethodInvocation}, not its statement.
     */
    private void delete(StatementBlock stmt, BodyBlock body, int index) {
        Statement s = stmt.enclosingStatement();
        if (stmt.isReadOnly()) {
            status("Can't delete generated code — it's maintained by Studio.");
            return;
        }
        if (s == null) {
            status("Can't delete this row on its own.");
            return;
        }
        state.setInsertionCursor(new InsertionCursor(body, Math.max(-1, index - 1)));
        context.getCodeEditor().deleteStatement(s);
    }

    /** Alt+↑/↓: reorders the block the caret sits on. */
    private void moveFocused(int delta) {
        InsertionCursor c = cursor();
        StatementBlock stmt = focusedStatement();
        if (c == null || stmt == null) {
            status("Nothing to move — the caret is on an empty slot.");
            return;
        }
        moveStatement(stmt, c.body(), c.index(), delta);
    }

    /**
     * Reorders {@code stmt} within its own body by one slot through the same {@code CodeEditor.moveStatement}
     * drag-and-drop uses. The insert index is the drop index a drag would produce: moving down inserts one
     * further along, because the removal of the original is still pending in the same rewrite.
     */
    private void moveStatement(StatementBlock stmt, BodyBlock body, int index, int delta) {
        if (stmt == null || body == null) return;
        int destination = index + delta;
        if (destination < 0 || destination >= body.getStatements().size()) {
            status(delta < 0 ? "Already the first block here." : "Already the last block here.");
            return;
        }
        if (stmt.isReadOnly() || body.isReadOnly()) {
            status("Can't move generated code — it's maintained by Studio.");
            return;
        }
        pendingInsert = new PendingFocus(new BlockTree.Position(index().ordinalOf(body), destination), Landed.MOVED);
        context.getCodeEditor().moveStatement(stmt, body, body, delta < 0 ? destination : destination + 1);
    }

    /** ▸/▾: hides or re-shows a control-flow block's branches. See {@link #collapsed}. */
    private void toggleFold(StatementBlock stmt) {
        BlockTree.Position at = index().locate(stmt);
        if (at == null) return;
        if (!collapsed.remove(at)) collapsed.add(at);
        render();
    }

    private boolean isCollapsed(StatementBlock stmt) {
        BlockTree.Position at = index().locate(stmt);
        return at != null && collapsed.contains(at);
    }

    // ── cursor ──────────────────────────────────────────────────────────────────────────────────────────

    private InsertionCursor cursor() {
        return state.getInsertionCursor().orElse(null);
    }

    /** The statement the caret sits on, or {@code null} at an empty or end slot. */
    private StatementBlock focusedStatement() {
        InsertionCursor c = cursor();
        if (c == null) return null;
        List<StatementBlock> statements = c.body().getStatements();
        return (c.index() >= 0 && c.index() < statements.size()) ? statements.get(c.index()) : null;
    }

    /**
     * Seeds the caret when none is set, or when it dangles after a re-parse or sits outside the method shown —
     * a caret in another method is real but off screen, and every insert would land where the user can't see.
     */
    private void ensureCursor() {
        InsertionCursor c = cursor();
        BodyBlock scope = index().methodBody(selectedMethod);
        boolean live = c != null && index().contains(c.body());
        if (live && (scope == null || BlockTree.containsDescendant(scope, c.body()))) return;
        state.setInsertionCursor(scope != null
                ? index().methodCursor(selectedMethod)
                : CursorNavigator.defaultCursor(root));
    }

    private void move(InsertionCursor next) {
        if (next != null) state.setInsertionCursor(next);
        render();
    }

    /**
     * Hides / restores the panel (and any open config popover) around a capture draw surface. The stage is
     * hidden with its teardown suppressed; the popover — which owns the modal capture surface — is only dimmed,
     * so its modal child stays alive. Only a panel this hid is shown again: any capture surface in Studio fires
     * this, and showing a panel the user had closed brought back a dead one.
     */
    private void hideForCapture(boolean hide) {
        config.dim(hide);
        Stage stage = stage();
        if (hide) {
            if (stage == null || !stage.isShowing()) return;
            hiddenForCapture = true;
            suppressHideTeardown = true;
            stage.hide();
        } else {
            if (!hiddenForCapture) return;
            hiddenForCapture = false;
            suppressHideTeardown = false;
            stage.show();
            stage.toFront();
        }
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────────────────────

    /** A republished block tree: re-render, then focus whatever an edit just placed. */
    private void onBlocksUpdated() {
        refreshMethods();
        render();
        // Before the pending handling below, which may open a popover of its own over this one.
        config.refresh();
        if (pendingInsert != null) {
            BlockTree.Position p = pendingInsert.at();
            Landed kind = pendingInsert.kind();
            pendingInsert = null;
            MethodSignature ov = kind == Landed.ADDED ? pendingOverload : null;
            pendingOverload = null;
            BodyBlock body = index().bodyAt(p.bodyOrdinal());
            if (body == null) return;
            List<StatementBlock> statements = body.getStatements();
            if (p.index() < 0 || p.index() >= statements.size()) return;
            // Re-home the caret onto the block so the next one goes below it.
            state.setInsertionCursor(new InsertionCursor(body, p.index()));
            render();
            StatementBlock landed = statements.get(p.index());
            status((kind == Landed.MOVED ? "Moved " : "Inserted ") + OverlayTreeView.compactLabel(landed));
            if (ov != null && landed instanceof MethodInvocationBlock mib) {
                // Applying the overload is another edit, so the block is about to be replaced: open its
                // popover on the next pulse instead of onto a dead block.
                mib.switchToOverload(context, ov);
                if (autoFillArgs.isSelected()) pendingConfig = p;
            } else if (kind == Landed.ADDED && autoFillArgs.isSelected() && landed instanceof MethodInvocationBlock mib) {
                config.open(mib);
            }
        } else if (pendingConfig != null) {
            BlockTree.Position p = pendingConfig;
            pendingConfig = null;
            if (index().statementAt(p) instanceof MethodInvocationBlock mib) config.open(mib);
        }
    }

    private void render() {
        ensureCursor();
        if (root == null) {
            tree.showMessage("No open file.");
            return;
        }
        BodyBlock scoped = index().methodBody(selectedMethod);
        if (scoped != null) {
            tree.render(List.of(scoped), cursor(), this::isCollapsed);
            return;
        }
        List<BodyBlock> tops = index().topLevelBodies();
        if (tops.isEmpty()) tree.showMessage("Program is empty.");
        else tree.render(tops, cursor(), this::isCollapsed);
    }

    /** {@link #root}'s structural index, rebuilt once per published tree rather than per lookup. */
    private BlockTree.Index index() {
        if (index == null || indexedRoot != root) {
            indexedRoot = root;
            index = BlockTree.index(root);
        }
        return index;
    }
}
