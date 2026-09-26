package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.debug.DebugSnapshot;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The <b>Debug</b> bottom tab (2026-09-26): while the bot is paused, the paused thread's call stack on the
 * left and the selected frame's variables on the right, one level of fields deep. Picking a frame of the
 * bot's own code also shows its block. Filled from {@link CoreApplicationEvents.DebugSnapshotEvent}; greyed
 * on resume and emptied when the session ends, because a stale stack read as current is worse than none.
 *
 * <p>Stepping stays on the toolbar; this tab only reads.
 */
final class DebugPanel {

    private static final String IDLE = "Run with ▶ Debug and stop at a breakpoint to see the call stack and "
            + "variables here.";

    private final Label summary = new Label(IDLE);
    private final ListView<DebugSnapshot.Frame> frames = new ListView<>();
    private final TreeTableView<DebugSnapshot.Variable> variables = new TreeTableView<>();
    private final SplitPane split;
    private final VBox node;
    /** True while a pause fills the list, whose first selection must not move the canvas again. */
    private boolean filling;

    DebugPanel(EventBus bus, Consumer<DebugSnapshot.Frame> onReveal) {
        summary.getStyleClass().add("review-summary");
        HBox bar = new HBox(summary);
        bar.getStyleClass().add("diagnostics-filter-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        frames.getStyleClass().add("debug-frames");
        frames.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(DebugSnapshot.Frame frame, boolean empty) {
                super.updateItem(frame, empty);
                setText(empty || frame == null ? null : frame.label());
                setOpacity(empty || frame == null || frame.inBot() ? 1.0 : 0.6);
            }
        });
        frames.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            showVariables(now);
            if (now != null && now.inBot() && !filling) onReveal.accept(now);
        });

        TreeTableColumn<DebugSnapshot.Variable, String> name = column("Name", DebugSnapshot.Variable::name, 160);
        TreeTableColumn<DebugSnapshot.Variable, String> type = column("Type", DebugSnapshot.Variable::type, 120);
        TreeTableColumn<DebugSnapshot.Variable, String> value = column("Value", DebugSnapshot.Variable::value, 320);
        variables.getColumns().setAll(name, type, value);
        variables.setShowRoot(false);
        variables.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        variables.setPlaceholder(new Label("No variables."));
        frames.setPlaceholder(new Label(""));

        split = new SplitPane(frames, variables);
        split.setDividerPositions(0.3);
        VBox.setVgrow(split, Priority.ALWAYS);
        node = new VBox(bar, split);

        bus.subscribe(CoreApplicationEvents.DebugSnapshotEvent.class, e -> show(e.snapshot()), true);
        bus.subscribe(CoreApplicationEvents.DebugSessionResumedEvent.class, e -> resumed(), true);
        bus.subscribe(CoreApplicationEvents.DebugSessionFinishedEvent.class, e -> clear(), true);
    }

    private static TreeTableColumn<DebugSnapshot.Variable, String> column(
            String title, java.util.function.Function<DebugSnapshot.Variable, String> read, double width) {
        TreeTableColumn<DebugSnapshot.Variable, String> column = new TreeTableColumn<>(title);
        column.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue() == null || c.getValue().getValue() == null ? "" : read.apply(c.getValue().getValue())));
        column.setPrefWidth(width);
        return column;
    }

    VBox node() {
        return node;
    }

    /** A pause: its frames, the innermost of the bot's own selected. */
    void show(DebugSnapshot snapshot) {
        split.setDisable(false);
        frames.getItems().setAll(snapshot.frames());
        summary.setText("Paused" + (snapshot.thread().isBlank() ? "" : " in thread \"" + snapshot.thread() + "\"")
                + " — " + snapshot.frames().size() + " frames");
        DebugSnapshot.Frame first = snapshot.frames().stream().filter(DebugSnapshot.Frame::inBot).findFirst()
                .orElse(snapshot.frames().isEmpty() ? null : snapshot.frames().getFirst());
        // Selected without revealing: the pause itself already brought the canvas to this block.
        filling = true;
        try {
            frames.getSelectionModel().clearSelection();
            if (first != null) frames.getSelectionModel().select(first);
        } finally {
            filling = false;
        }
    }

    private void resumed() {
        if (frames.getItems().isEmpty()) return;
        summary.setText("Running…");
        split.setDisable(true);
    }

    private void clear() {
        frames.getItems().clear();
        showVariables(null);
        summary.setText(IDLE);
        split.setDisable(false);
    }

    private void showVariables(DebugSnapshot.Frame frame) {
        TreeItem<DebugSnapshot.Variable> root = new TreeItem<>();
        if (frame != null) for (DebugSnapshot.Variable v : frame.variables()) root.getChildren().add(item(v));
        variables.setRoot(root);
    }

    private static TreeItem<DebugSnapshot.Variable> item(DebugSnapshot.Variable v) {
        TreeItem<DebugSnapshot.Variable> item = new TreeItem<>(v);
        for (DebugSnapshot.Variable child : v.children()) item.getChildren().add(item(child));
        return item;
    }

    /** What the variables table lists now, top level, for a test. */
    java.util.List<String> shownVariables() {
        return variables.getRoot() == null ? java.util.List.of()
                : variables.getRoot().getChildren().stream().map(i -> i.getValue().name()).toList();
    }

    String summary() {
        return summary.getText();
    }
}
