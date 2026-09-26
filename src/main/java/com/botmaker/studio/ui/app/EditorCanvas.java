package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.dnd.BlockEvent;
import com.botmaker.studio.ui.render.theme.BlockStyle;
import com.botmaker.studio.ui.render.theme.BlockStylePreference;
import com.botmaker.studio.ui.render.theme.BlockFont;
import com.botmaker.studio.ui.render.theme.BlockFontPreference;
import com.botmaker.studio.ui.render.theme.CanvasZoom;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Supplier;

/**
 * The centre column: the scrolling block canvas, the Reader-mode banner for an installed bot opened
 * read-only, and the missing-plugin banner for a project whose data has an owner that is not loaded.
 *
 * <p>It re-renders the whole program on every {@code UIBlocksUpdatedEvent}, which is what makes the scroll
 * position its problem: the swap empties the {@link ScrollPane}, so the viewport's position is lost unless
 * something puts it back. See {@link #handleBlocksUpdate}.
 */
final class EditorCanvas {

    private final CodeEditorService codeEditorService;
    private final EventBus eventBus;

    private final VBox blocksContainer;
    private final ZoomPane zoomPane;
    private final ScrollPane scrollPane;
    private final VBox column;

    /** Above the canvas whenever the project holds data for a plugin that is not loaded; null when it does not. */
    private HBox missingPluginBanner;

    /**
     * @param readerMode        renders without controls, under a banner offering the switch to Editor mode
     * @param projectName       named in that banner
     * @param onSwitchToEditor  the banner's button
     * @param missingPlugins    the ids this project holds data for that nothing answers for — asked again on
     *                          every {@code LibrariesChangedEvent}, since installing the plugin is exactly
     *                          what the banner's button leads to
     * @param onManagePlugins   that banner's button — the registry browser, where the plugin is installed
     */
    EditorCanvas(CodeEditorService codeEditorService, EventBus eventBus,
                 boolean readerMode, String projectName, Runnable onSwitchToEditor,
                 Supplier<List<String>> missingPlugins, Runnable onManagePlugins) {
        this.codeEditorService = codeEditorService;
        this.eventBus = eventBus;

        this.blocksContainer = new VBox(10);
        blocksContainer.getStyleClass().add("blocks-canvas");
        blocksContainer.setPadding(new Insets(20));
        followBlockStyle(blocksContainer);
        followBlockFont(blocksContainer);

        // Accept block drags over the whole canvas so the OS "forbidden" cursor doesn't flash over gaps/padding.
        // Real drop zones (separators / block hitboxes) sit on top and consume the event; this only fires over
        // bare canvas, where a release is simply a no-op (no onDragDropped here).
        blocksContainer.setOnDragOver(e -> {
            var db = e.getDragboard();
            if (db.hasContent(BlockDragAndDropManager.ADDABLE_BLOCK_FORMAT)
                    || db.hasContent(BlockDragAndDropManager.EXISTING_BLOCK_FORMAT)) {
                e.acceptTransferModes(TransferMode.COPY, TransferMode.MOVE);
            }
        });

        // Zoom: the canvas is drawn CanvasZoom.factor() times its size and laid out at the width that leaves,
        // so it re-wraps rather than overflowing — see ZoomPane. bind() holds the static property weakly, so a
        // canvas thrown away on reload is not kept alive by the preference it follows.
        this.zoomPane = new ZoomPane(blocksContainer);
        zoomPane.zoomProperty().bind(CanvasZoom.factorProperty());

        this.scrollPane = new ScrollPane(zoomPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.getStyleClass().add("code-scroll-pane");
        installZoomGestures();

        // Reader mode: a full-colour, control-free view of someone else's bot. A single banner carries the
        // state; the blocks themselves render without any controls (LockResolver suppresses interaction).
        StackPane viewport = new StackPane(scrollPane, zoomBadge());
        this.column = new VBox(viewport);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        if (readerMode) {
            blocksContainer.getStyleClass().add("reader-mode");
            column.getChildren().addFirst(readerBanner(projectName, onSwitchToEditor));
        }
        // The too-old-SDK banner that used to be added here went on 2026-08-25 with the version floor
        // itself: with no generation left in Studio there is nothing a version comparison protects, and the
        // palette's answer is per element, from the project's own jar. See MavenService.

        // The missing-plugin banner takes its place, and it is a different kind of claim: not "this version
        // is too old" but "this project's data has an owner who is not here". It is re-asked rather than
        // computed once, because the pom can grow the plugin while the project stays open — installing it is
        // the banner's own button, and a banner that outlived its cause would be the worst outcome.
        showMissingPlugins(missingPlugins.get(), onManagePlugins);
        eventBus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class,
                e -> showMissingPlugins(missingPlugins.get(), onManagePlugins), true);
        followSubscription = eventBus.subscribe(CoreApplicationEvents.ExecutionFollowedEvent.class,
                e -> follow(e.block()), true);
    }

    /** Drops what this canvas subscribed to on the project's bus: the next window builds its own. */
    void dispose() {
        followSubscription.close();
        if (followScroll != null) followScroll.stop();
    }

    // --- following a run --------------------------------------------------------------------------------------

    /** Scrolls no more often than this while following, whatever the bot does. */
    private static final long FOLLOW_SCROLL_GAP_MS = 400;
    private static final Duration FOLLOW_SCROLL_TIME = Duration.millis(200);
    /** The band, as a fraction of the viewport from each edge, a followed block may sit in without a scroll. */
    private static final double FOLLOW_MARGIN = 0.2;

    private final EventBus.Subscription followSubscription;
    private Timeline followScroll;
    private long lastFollowScroll;

    /**
     * Keeps the block a session is on in view, without making the canvas jump: nothing moves while the block
     * sits in the middle 60 % of the viewport; otherwise one eased 200 ms scroll centres it, and never more
     * than one per {@link #FOLLOW_SCROLL_GAP_MS}. A block that has moved off-screen by then is caught by the
     * next follow.
     */
    private void follow(CodeBlock block) {
        Node node = block == null ? null : block.getUINode();
        if (node == null || node.getScene() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastFollowScroll < FOLLOW_SCROLL_GAP_MS) return;
        Bounds bounds = zoomPane.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double contentH = zoomPane.getHeight();
        double viewportH = scrollPane.getViewportBounds().getHeight();
        if (bounds == null || contentH <= viewportH) return;
        double top = scrollPane.getVvalue() * (contentH - viewportH);
        double centre = (bounds.getMinY() + bounds.getMaxY()) / 2;
        double margin = viewportH * FOLLOW_MARGIN;
        if (centre >= top + margin && centre <= top + viewportH - margin) return;
        double target = Math.max(0, Math.min(1, (centre - viewportH / 2) / (contentH - viewportH)));
        lastFollowScroll = now;
        if (followScroll != null) followScroll.stop();
        followScroll = new Timeline(new KeyFrame(FOLLOW_SCROLL_TIME,
                new KeyValue(scrollPane.vvalueProperty(), target, Interpolator.EASE_BOTH)));
        followScroll.play();
    }

    /**
     * Puts the missing-plugin banner up, takes it down, or replaces it — whichever the current list asks for.
     *
     * <p>Below the reader banner and above the canvas: two banners can be true at once (someone else's bot,
     * opened read-only, whose plugin this Studio's user has not installed either) and the reader banner is
     * the one about what the user may do at all.
     */
    private void showMissingPlugins(List<String> missing, Runnable onManagePlugins) {
        if (missingPluginBanner != null) {
            column.getChildren().remove(missingPluginBanner);
            missingPluginBanner = null;
        }
        if (missing == null || missing.isEmpty()) return;
        missingPluginBanner = missingPluginBanner(missing, onManagePlugins);
        column.getChildren().add(column.getChildren().isEmpty() ? 0 : column.getChildren().size() - 1,
                missingPluginBanner);
    }

    /**
     * The "this project has data you cannot see" banner.
     *
     * <p>It names the plugins rather than counting them, because the id is what the user types into Manage
     * Plugins and the only thing that makes the sentence actionable. What it deliberately does not say is
     * <i>what</i> the data is: the host has not read a byte of it and must not guess — see
     * {@code PluginOwners}.
     */
    private static HBox missingPluginBanner(List<String> missing, Runnable onManagePlugins) {
        String names = String.join(", ", missing);
        Label msg = new Label(missing.size() == 1
                ? "This project holds data owned by “" + names + "”, which is not installed. "
                        + "That data is kept as it is, and everything else keeps working."
                : "This project holds data owned by plugins that are not installed: " + names + ". "
                        + "That data is kept as it is, and everything else keeps working.");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button install = new Button("Install…");
        install.setOnAction(e -> onManagePlugins.run());
        HBox banner = new HBox(10, msg, spacer, install);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("missing-plugin-banner");
        return banner;
    }

    VBox node() {
        return column;
    }

    /**
     * The state the canvas is in between the window appearing and the first file being parsed.
     *
     * <p>Project open shows the shell before it has any blocks to put in it — parsing the entry point with
     * bindings is the slow step and it runs a pulse later, so the frame in between would otherwise be an empty
     * white canvas indistinguishable from a broken open. There is no matching {@code hideLoading}: the first
     * {@link #handleBlocksUpdate} clears the container, which removes this along with everything else.
     */
    void showLoading() {
        Label loading = new Label("Loading project…");
        loading.getStyleClass().add("canvas-placeholder");
        VBox centred = new VBox(loading);
        centred.setAlignment(Pos.CENTER);
        centred.setPadding(new Insets(60, 0, 0, 0));
        blocksContainer.getChildren().setAll(centred);
    }

    /**
     * Re-renders the program.
     *
     * <p>The vertical position is captured and restored across the swap. Clearing the container collapses the
     * {@code ScrollPane}'s content height to zero, and {@code vvalue} is clamped against that height — so the
     * canvas silently jumped back to the top after every single edit. The restore runs on the next pulse,
     * once the new root node has been laid out and the scrollable range exists again.
     *
     * <p><b>Block reuse did not make this redundant</b> (2026-09-15), which the plan for it expected. Member
     * blocks are deliberately never reused — {@code BlockConverter.parseRoot} builds them fresh — so the node
     * swapped in here is always new and always unlaid-out, whatever survives underneath it. Deleting the two
     * lines was tried and measured: the canvas jumps to the top. {@code CanvasScrollTest} holds both that and
     * its cause. See {@code docs/refactor/30-block-reuse.md} §10.
     */
    void handleBlocksUpdate(CoreApplicationEvents.UIBlocksUpdatedEvent event) {
        double vvalue = scrollPane.getVvalue();
        blocksContainer.getChildren().clear();
        if (event.rootBlock() != null) {
            Node rootNode = event.rootBlock().getUINode(codeEditorService);
            rootNode.addEventHandler(BlockEvent.BreakpointToggleEvent.TOGGLE_BREAKPOINT, e ->
                    eventBus.publish(new CoreApplicationEvents.BreakpointToggledEvent(e.getBlock(), e.isEnabled())));
            blocksContainer.getChildren().add(rootNode);
        }
        // Lay the new tree out now, so the scroll range is the real one when the position goes back; then once
        // more after the pulse, for anything that only settles there. The pulse alone was a race: a first
        // layout that took longer (a canvas of fitted fields and dropdowns measures each one) left the range
        // collapsed when the restore ran, and CanvasScrollTest saw the canvas jump to the top.
        scrollPane.applyCss();
        scrollPane.layout();
        scrollPane.setVvalue(vvalue);
        Platform.runLater(() -> scrollPane.setVvalue(vvalue));
    }

    /**
     * Brings {@code block} into view when its error is clicked in the Errors panel: highlights it (reusing the
     * debugger's {@link CoreApplicationEvents.BlockHighlightEvent} path) and scrolls the canvas so the block is
     * visible. Runs the scroll on the next pulse so the node's layout bounds are current.
     */
    void scrollToBlock(CodeBlock block) {
        if (block == null) return;
        CodeBlock target = block.getHighlightTarget();
        eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
        Node node = target != null ? target.getUINode() : null;
        if (node == null) return;
        Platform.runLater(() -> {
            // Measured in the zoom pane, not the container: the container's own bounds are pre-scale, and the
            // scroll range is the scaled height.
            Bounds nodeInContent = zoomPane.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double contentH = zoomPane.getHeight();
            double viewportH = scrollPane.getViewportBounds().getHeight();
            if (contentH > viewportH) {
                double vvalue = (nodeInContent.getMinY() - 20) / (contentH - viewportH);
                scrollPane.setVvalue(Math.max(0, Math.min(1, vvalue)));
            }
            node.requestFocus();
        });
    }

    // --- zoom ------------------------------------------------------------------------------------------------

    /**
     * Ctrl+wheel and a trackpad pinch zoom around the pointer; Ctrl+plus/minus/0 from the numeric keypad reach
     * what the View menu's accelerators cannot (a menu item has one key, and the keypad's is a different code).
     * Filters, so a slot's own scroll or key handling never sees a zoom gesture.
     */
    private void installZoomGestures() {
        scrollPane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isShortcutDown() || e.getDeltaY() == 0) return;
            double factor = e.getDeltaY() > 0 ? CanvasZoom.stepUp(CanvasZoom.factor())
                    : CanvasZoom.stepDown(CanvasZoom.factor());
            zoomAround(factor, e.getY());
            e.consume();
        });
        scrollPane.addEventFilter(ZoomEvent.ZOOM, e -> {
            zoomAround(CanvasZoom.factor() * e.getZoomFactor(), e.getY());
            e.consume();
        });
        scrollPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!e.isShortcutDown()) return;
            switch (e.getCode()) {
                case ADD, PLUS -> CanvasZoom.zoomIn();
                case SUBTRACT -> CanvasZoom.zoomOut();
                case NUMPAD0 -> CanvasZoom.reset();
                default -> { return; }
            }
            e.consume();
        });
    }

    /**
     * Sets {@code factor} and scrolls so the program line under {@code pointerY} (in the scroll pane's
     * coordinates) is still under it afterwards — without this, zooming at the bottom of a long method throws
     * the reader back to wherever the proportional scroll position happens to land.
     */
    private void zoomAround(double factor, double pointerY) {
        double viewportH = scrollPane.getViewportBounds().getHeight();
        double top = scrollPane.getVvalue() * Math.max(0, zoomPane.getHeight() - viewportH);
        double programY = (top + pointerY) / CanvasZoom.factor();

        CanvasZoom.set(factor);
        scrollPane.layout();

        double range = zoomPane.getHeight() - viewportH;
        double wantedTop = programY * CanvasZoom.factor() - pointerY;
        scrollPane.setVvalue(range > 0 ? Math.max(0, Math.min(1, wantedTop / range)) : 0);
    }

    /**
     * Keeps {@code canvas} carrying the current {@link BlockStyle}'s class — the one switch between the filled
     * and outlined rules in {@code blocks.css}. Weak for the badge's reason: the canvas is rebuilt on every
     * reload and the preference is process-lived.
     */
    static void followBlockStyle(Node canvas) {
        Runnable refresh = () -> {
            canvas.getStyleClass().removeAll(BlockStyle.styleClasses());
            canvas.getStyleClass().add(BlockStylePreference.style().styleClass());
        };
        refresh.run();
        InvalidationListener listener = obs -> refresh.run();
        canvas.getProperties().put("block-style-listener", listener);
        BlockStylePreference.styleProperty().addListener(new WeakInvalidationListener(listener));
    }

    /**
     * Keeps {@code canvas} written in the current {@link BlockFont}: its family and base size inline, since the
     * family is an open set no stylesheet can list, and its class, which is what selects Nunito's own heavier
     * families. Weak for {@link #followBlockStyle}'s reason.
     */
    static void followBlockFont(Node canvas) {
        Runnable refresh = () -> BlockFontPreference.font().applyTo(canvas);
        refresh.run();
        InvalidationListener listener = obs -> refresh.run();
        canvas.getProperties().put("block-font-listener", listener);
        BlockFontPreference.fontProperty().addListener(new WeakInvalidationListener(listener));
    }

    /**
     * Bottom-right "− 100% +" control, always shown: the only zoom a user finds without knowing a shortcut.
     * The percentage resets on click; each end is disabled at its limit.
     */
    private static Node zoomBadge() {
        Button out = zoomButton("−", "Zoom out (Ctrl+-)", CanvasZoom::zoomOut);
        Button in = zoomButton("+", "Zoom in (Ctrl+=)", CanvasZoom::zoomIn);
        Button percent = zoomButton("", "Reset zoom (Ctrl+0)", CanvasZoom::reset);
        percent.getStyleClass().add("zoom-percent");
        HBox control = new HBox(out, percent, in);
        control.getStyleClass().add("zoom-badge");
        control.setAlignment(Pos.CENTER);
        control.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Runnable refresh = () -> {
            double factor = CanvasZoom.factor();
            percent.setText(CanvasZoom.percent(factor));
            out.setDisable(factor <= CanvasZoom.MIN);
            in.setDisable(factor >= CanvasZoom.MAX);
        };
        refresh.run();
        // Weak: the control dies with its canvas, the preference does not.
        InvalidationListener listener = obs -> refresh.run();
        control.getProperties().put("zoom-listener", listener);
        CanvasZoom.factorProperty().addListener(new WeakInvalidationListener(listener));
        StackPane.setAlignment(control, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(control, new Insets(0, 18, 18, 0));
        return control;
    }

    private static Button zoomButton(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("zoom-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> action.run());
        return button;
    }

    /** The "Reading — switch to Editor to change" banner shown above the canvas for an installed bot. */
    private static HBox readerBanner(String projectName, Runnable onSwitchToEditor) {
        Label msg = new Label("Reading “" + projectName
                + "”. Switch to Editor mode to make it yours and start changing it.");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toEditor = new Button("Switch to Editor mode");
        toEditor.setOnAction(e -> onSwitchToEditor.run());
        HBox banner = new HBox(10, msg, spacer, toEditor);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("reader-banner");
        return banner;
    }

}
