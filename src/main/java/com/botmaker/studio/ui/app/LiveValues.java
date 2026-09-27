package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.debug.DebugSnapshot;
import com.botmaker.studio.services.debug.LiveScope;
import com.botmaker.studio.services.debug.ValueSummary;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The paused frame's values on the canvas (2026-09-27): a chip beside each declaration, assignment and name
 * block whose local is live at the paused line, reading "found at 120, 340 · 93%" or "3", with the whole
 * value in its tooltip. Only while paused: a resume, a finish or a new session takes every chip off.
 *
 * <p>The values are the {@link DebugSnapshot} the Debug tab shows, the top frame only and only when it is in
 * the file the canvas has open; nothing here reads the VM. Which blocks get one is {@link LiveScope}'s, what a
 * chip says {@link ValueSummary}'s. A re-render while paused (an edit, a file switch) draws new block nodes,
 * so the chips are put back on the new ones.
 */
final class LiveValues {

    static final String CHIP_CLASS = "live-value-chip";

    private final ProjectState state;
    private final List<Label> chips = new ArrayList<>();
    private DebugSnapshot snapshot;
    private boolean paused;

    LiveValues(EventBus bus, ProjectState state) {
        this.state = state;
        bus.subscribe(CoreApplicationEvents.DebugSnapshotEvent.class, e -> snapshot = e.snapshot(), true);
        bus.subscribe(CoreApplicationEvents.DebugSessionPausedEvent.class, e -> {
            paused = true;
            show();
        }, true);
        bus.subscribe(CoreApplicationEvents.DebugSessionResumedEvent.class, e -> stop(), true);
        bus.subscribe(CoreApplicationEvents.DebugSessionFinishedEvent.class, e -> stop(), true);
        bus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> stop(), true);
        // After the canvas has drawn the new blocks, whose subscription runs first.
        bus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, e -> {
            if (paused) Platform.runLater(this::show);
        }, true);
    }

    private void stop() {
        paused = false;
        snapshot = null;
        clear();
    }

    private void clear() {
        for (Label chip : chips) {
            if (chip.getParent() instanceof Pane pane) pane.getChildren().remove(chip);
        }
        chips.clear();
    }

    private void show() {
        clear();
        if (!paused || snapshot == null || snapshot.frames().isEmpty()) return;
        DebugSnapshot.Frame top = snapshot.frames().getFirst();
        ProjectFile active = state.getActiveFile();
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        if (!top.inBot() || active == null || !top.file().equals(active.getPath()) || cu == null) return;

        Map<String, DebugSnapshot.Variable> byName = new HashMap<>();
        for (DebugSnapshot.Variable v : top.variables()) byName.put(v.name(), v);
        for (CodeBlock block : state.getNodeToBlockMap().values()) {
            if (block == null) continue;
            List<DebugSnapshot.Variable> values = LiveScope.shownBy(block.getAstNode(), block instanceof ExpressionBlock)
                    .stream()
                    .filter(name -> LiveScope.isLive(name, cu, top.line()))
                    .map(name -> byName.get(name.getIdentifier()))
                    .filter(v -> v != null)
                    .toList();
            if (!values.isEmpty()) attach(block.getUINode(), values);
        }
    }

    /** One chip at the end of the block's own row, before any spacer that pushes its ✕ to the right. */
    private void attach(Node node, List<DebugSnapshot.Variable> values) {
        if (!(node instanceof HBox row)) return;
        String text = values.size() == 1 ? ValueSummary.of(values.getFirst())
                : values.stream().map(v -> v.name() + " = " + ValueSummary.of(v)).collect(Collectors.joining(", "));
        Label chip = new Label(text);
        chip.getStyleClass().add(CHIP_CLASS);
        chip.setMinWidth(Label.USE_PREF_SIZE);
        Tooltip tip = new Tooltip(values.stream().map(ValueSummary::tree).collect(Collectors.joining("\n")));
        tip.setShowDelay(Duration.millis(200));
        Tooltip.install(chip, tip);
        row.getChildren().add(endOfSentence(row), chip);
        chips.add(chip);
    }

    /**
     * Where a block's words end: before the empty spacer a header puts ahead of its right-hand controls, else
     * before its ✕, else at the end. A growing field is part of the sentence, so it is not the place.
     */
    private static int endOfSentence(HBox row) {
        List<Node> children = row.getChildren();
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            if (child.getClass() == Pane.class && ((Pane) child).getChildren().isEmpty()
                    && HBox.getHgrow(child) == Priority.ALWAYS) {
                return i;
            }
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).getStyleClass().contains("block-delete-button")) return i;
        }
        return children.size();
    }
}
