package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.ReviewService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The <b>Review</b> bottom tab: everything BotMaker changed for the user and could not finish on its own.
 *
 * <p>This is the half of the promise the marks exist for. A refactor that rewrites files the user is not
 * looking at and guesses — an SDK upgrade, a signature edit, a template repoint — leaves {@code @Refactor}
 * behind ({@code parser/refactor/ReviewMarks}); without somewhere to see them the user would have to open
 * every file in the bot to find out what happened. Here each function with an open mark is one row: where,
 * what the refactor guessed. A click opens what can be done about it ({@link #menuFor}, 2026-10-05): go to it,
 * mark it reviewed ({@code done = true}, which keeps the record in the code and drops the row), remove the
 * mark, undo the change from the version taken before it, or rename or delete the function.
 *
 * <p><b>Nothing is cached.</b> The list is re-scanned from the sources on every {@link #refresh}, and the tab
 * refreshes when it is opened. Four different code paths write marks, two of them without the editor
 * involved, so a panel holding its own copy would be wrong more often than right — see
 * {@link ReviewService}.
 */
final class ReviewPanel {

    /**
     * What the window does with a row's function — the canvas's own gestures, so a rename or delete from here
     * is the one the function's header makes (call sites, refusals, ↶ included).
     */
    interface Actions {
        /** Opens the marked function on the canvas. */
        void reveal(ReviewService.Item item);

        /** Renames the function and its calls, as its header's signature edit does. */
        void rename(ReviewService.Item item, String newName);

        /** Deletes the function, refusing while something calls it, as its header's × does. */
        void delete(ReviewService.Item item);

        /** Draws {@code file} again from its buffer, after a rewrite from here. */
        void redraw(Path file);
    }

    private final ProjectConfig config;
    private final ProjectState state;
    private final Actions actions;

    private final ListView<ReviewService.Item> list = new ListView<>();
    private final Label summary = new Label();
    private final VBox node;

    ReviewPanel(ProjectConfig config, ProjectState state, Actions actions) {
        this.config = config;
        this.state = state;
        this.actions = actions;

        configureList();
        VBox.setVgrow(list, Priority.ALWAYS);

        summary.getStyleClass().add("review-summary");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        // Same band as the Errors tab's filter bar, over the same tokens — see blocks.css.
        HBox bar = new HBox(summary, spacer, refresh);
        bar.getStyleClass().add("diagnostics-filter-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        this.node = new VBox(bar, list);
    }

    VBox node() {
        return node;
    }

    /** Re-reads the marks from the project's sources. Cheap enough to call whenever the tab is opened. */
    void refresh() {
        List<ReviewService.Item> items = ReviewService.scan(config, state);
        list.getItems().setAll(items);
        long files = items.stream().map(ReviewService.Item::file).distinct().count();
        summary.setText(items.isEmpty()
                ? "Nothing to review."
                : items.size() + (items.size() == 1 ? " thing" : " things") + " to look at in "
                        + files + (files == 1 ? " file." : " files."));
    }

    /** True when the project holds at least one mark — what decides whether the tab raises itself. */
    boolean hasItems() {
        return !list.getItems().isEmpty();
    }

    /** What a click on {@code item}'s row offers: every item a decision about that one function. */
    ContextMenu menuFor(ReviewService.Item item) {
        MenuItem goTo = new MenuItem("Go to it");
        goTo.setOnAction(e -> actions.reveal(item));
        MenuItem reviewed = new MenuItem("Mark reviewed");
        reviewed.setOnAction(e -> rewrite(item, "Before marking " + item.function() + "() reviewed",
                () -> ReviewService.markReviewed(config, state, item)));
        MenuItem unmark = new MenuItem("Remove the mark");
        unmark.setOnAction(e -> rewrite(item, "Before removing the mark on " + item.function() + "()",
                () -> ReviewService.removeMark(config, state, item)));
        MenuItem undo = new MenuItem("Undo this change…");
        undo.setOnAction(e -> undo(item));
        MenuItem rename = new MenuItem("Rename function…");
        rename.setOnAction(e -> rename(item));
        MenuItem delete = new MenuItem("Delete function");
        delete.setOnAction(e -> actions.delete(item));
        boolean editable = !state.isReaderMode();
        for (MenuItem writes : List.of(reviewed, unmark, undo, rename, delete)) writes.setDisable(!editable);
        return new ContextMenu(goTo, new SeparatorMenuItem(), reviewed, unmark, undo,
                new SeparatorMenuItem(), rename, delete);
    }

    /**
     * One rewrite from the menu: a safety version first (the file may not be the one on screen, so ↶ may not
     * reach it), then the change, then the file redrawn and the list re-read — the file is the truth.
     */
    private void rewrite(ReviewService.Item item, String label, BooleanSupplier change) {
        Checkpoints.take(config.projectPath(), state.snapshot(), VersionOrigin.SAFETY, label);
        if (change.getAsBoolean()) actions.redraw(item.file());
        refresh();
    }

    /** Asks first — the version it restores from is named — then puts the function back on a worker. */
    private void undo(ReviewService.Item item) {
        Alert ask = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, "Put " + item.function()
                + "() back as it was before this change?\n\nOnly this function changes; a version of the project"
                + " is saved first. If what it used is gone (a plugin removed), what comes back will not compile.");
        if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Checkpoints.take(config.projectPath(), state.snapshot(), VersionOrigin.SAFETY,
                "Before undoing the change to " + item.function() + "()");
        summary.setText("Looking for " + item.function() + "() in this bot's versions…");
        // The buffers are the FX thread's: read the signature here, the history on the worker, write back here.
        List<String> parameters = ReviewService.parametersOf(config, state, item);
        Thread worker = new Thread(() -> {
            ReviewService.Undo found = ReviewService.findEarlier(config, item, parameters);
            Platform.runLater(() -> {
                ReviewService.Undo outcome = found instanceof ReviewService.Undo.Ready ready
                        ? ReviewService.restore(config, state, item, ready.earlier()) : found;
                refresh();
                switch (outcome) {
                    case ReviewService.Undo.Done done -> {
                        actions.redraw(item.file());
                        summary.setText(item.function() + "() is back as \"" + done.version() + "\" had it.");
                    }
                    case ReviewService.Undo.Refused refused -> ThemedWindows.alert(Alert.AlertType.INFORMATION,
                            refused.reason()).showAndWait();
                    case ReviewService.Undo.Ready ignored -> { }
                }
            });
        }, "review-undo");
        worker.setDaemon(true);
        worker.start();
    }

    private void rename(ReviewService.Item item) {
        TextInputDialog dialog = new TextInputDialog(item.function());
        ThemedWindows.apply(dialog);
        if (node.getScene() != null) dialog.initOwner(node.getScene().getWindow());
        dialog.setTitle("Rename function");
        dialog.setHeaderText("Rename " + item.function() + "() and every call to it");
        dialog.setContentText("New name:");
        String newName = dialog.showAndWait().map(String::strip).orElse("");
        if (!newName.isEmpty() && !newName.equals(item.function())) actions.rename(item, newName);
    }

    private void configureList() {
        list.setPlaceholder(new Label("Nothing to review — BotMaker finished everything it changed."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ReviewService.Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setOnMouseClicked(null);
                    return;
                }
                setText(null);
                setGraphic(row(item));
                setOnMouseClicked(e -> {
                    if (actions != null) menuFor(item).show(this, e.getScreenX(), e.getScreenY());
                });
            }
        });
    }

    /**
     * Two lines: where it is, and what happened. The entry is the sentence the refactor wrote — it is meant to
     * be read as prose, so it wraps rather than being clipped to the panel's width.
     */
    private static VBox row(ReviewService.Item item) {
        Label where = new Label(item.where());
        where.getStyleClass().add("review-cell-where");

        Label what = new Label(String.join("\n", item.entries()));
        what.getStyleClass().add("review-cell-what");
        what.setWrapText(true);

        VBox box = new VBox(2, where, what);
        box.getStyleClass().add("review-cell");
        return box;
    }
}
