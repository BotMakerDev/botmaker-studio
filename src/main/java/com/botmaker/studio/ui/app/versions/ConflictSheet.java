package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.project.vcs.BlockDiff;
import com.botmaker.studio.project.vcs.ProjectVcs.Side;
import com.botmaker.studio.project.vcs.VersionReader;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The files an update could not merge, decided one by one ({@code docs/refactor/39-versions.md} §7): each with
 * its §5 diff, <b>Yours | Theirs</b>, and <i>Keep mine</i> / <i>Take theirs</i>. <i>Finish update</i> is enabled
 * once every file is decided; <i>Cancel update</i> puts the project back as it was. Per file, not per function —
 * the maintainer's call; the block diff is what makes the file decision an informed one.
 */
final class ConflictSheet {

    private ConflictSheet() {}

    /**
     * Shows the sheet and waits.
     *
     * @param theirs the revision the author's side is read from ({@code MERGE_HEAD})
     * @param release how the author's side is named — {@code v1.4}
     * @return each file's decision, or empty when the update is cancelled
     */
    static Optional<Map<String, Side>> show(Window owner, DiffCards cards, Path projectDir, List<String> files,
                                            String theirs, String release) {
        Map<String, Side> decided = new LinkedHashMap<>();
        VersionReader reader = new VersionReader(projectDir);

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Update to " + release);
        dialog.setHeaderText("You and the author both changed " + files.size()
                + (files.size() == 1 ? " file" : " files") + ". Choose, for each, which one to keep.");
        dialog.setResizable(true);
        ButtonType finish = new ButtonType("Finish update", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel update", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(finish, cancel);
        dialog.getDialogPane().lookupButton(finish).setDisable(true);

        ListView<String> list = new ListView<>();
        list.getItems().setAll(files);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null);
                    return;
                }
                Side side = decided.get(path);
                setText((side == null ? "○ " : side == Side.MINE ? "● mine · " : "● theirs · ") + path);
            }
        });

        ToggleGroup choice = new ToggleGroup();
        ToggleButton mine = new ToggleButton("Keep mine");
        ToggleButton them = new ToggleButton("Take theirs");
        mine.setToggleGroup(choice);
        them.setToggleGroup(choice);
        HBox choices = new HBox(8, new Label("For this file:"), mine, them);
        choices.setAlignment(Pos.CENTER_LEFT);
        choices.setPadding(new Insets(6));

        ScrollPane view = new ScrollPane();
        view.setFitToWidth(true);
        VBox right = new VBox(choices, view);
        VBox.setVgrow(view, Priority.ALWAYS);

        list.getSelectionModel().selectedItemProperty().addListener((o, was, path) -> {
            if (path == null) return;
            Side side = decided.get(path);
            choice.selectToggle(side == Side.MINE ? mine : side == Side.THEIRS ? them : null);
            view.setContent(cards.build(input(reader, path, theirs, release), NO_RESTORE));
        });
        choice.selectedToggleProperty().addListener((o, was, now) -> {
            String path = list.getSelectionModel().getSelectedItem();
            if (path == null || now == null) return;
            decided.put(path, now == mine ? Side.MINE : Side.THEIRS);
            list.refresh();
            dialog.getDialogPane().lookupButton(finish).setDisable(decided.size() < files.size());
        });

        SplitPane split = new SplitPane(list, right);
        split.setDividerPositions(0.3);
        split.setPrefSize(1000, 620);
        dialog.getDialogPane().setContent(split);
        list.getSelectionModel().selectFirst();

        return dialog.showAndWait().filter(b -> b == finish).map(b -> Map.copyOf(decided));
    }

    private static final DiffCards.Actions NO_RESTORE = new DiffCards.Actions() {
        @Override
        public String restoreLabel(BlockDiff.MethodChange change) {
            return null;
        }

        @Override
        public void restoreFunction(BlockDiff.MethodChange change) {
        }
    };

    private static DiffCards.Input input(VersionReader reader, String path, String theirs, String release) {
        byte[] yours;
        byte[] their;
        try {
            yours = reader.at("HEAD", path);
            their = reader.at(theirs, path);
        } catch (IOException e) {
            return new DiffCards.Input(path, new VersionReader.Sides(path, path, null, null), null,
                    "Could not read " + path + ": " + e.getMessage(), "Yours", "Theirs (" + release + ")");
        }
        VersionReader.Sides sides = new VersionReader.Sides(path, path, yours, their);
        BlockDiff.FileDiff diff = DiffCards.isJava(path) ? BlockDiff.of(sides.beforeText(), sides.afterText()) : null;
        return new DiffCards.Input(path, sides, diff, VersionReader.unified(path, yours, their),
                "Yours", "Theirs (" + release + ")");
    }
}
