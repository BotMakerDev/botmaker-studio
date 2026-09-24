package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.render.theme.BlockFont;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Optional;

/**
 * View ▸ Block Font ▸ Other Installed Font…: every family on this machine in a list a search narrows, each
 * drawn in its own face with a line of block text, so choosing one is reading it.
 */
final class BlockFontDialog {

    private static final String SAMPLE = "repeat 10 times  ·  if count > 3";

    private BlockFontDialog() {}

    /** The family chosen, or empty when the user cancelled. */
    static Optional<BlockFont> ask(Window owner, BlockFont current) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Block Font");

        TextField search = new TextField();
        search.setPromptText("Search fonts…");
        FilteredList<String> families = new FilteredList<>(FXCollections.observableArrayList(Font.getFamilies()));
        search.textProperty().addListener((obs, was, query) -> {
            String q = query == null ? "" : query.strip().toLowerCase();
            families.setPredicate(f -> q.isEmpty() || f.toLowerCase().contains(q));
        });

        ListView<String> list = new ListView<>(families);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String family, boolean empty) {
                super.updateItem(family, empty);
                if (empty || family == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(family);
                name.getStyleClass().add("block-font-name");
                Label sample = new Label(SAMPLE);
                sample.setFont(Font.font(family, 14));
                setText(null);
                setGraphic(new VBox(1, name, sample));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        if (current.family() != null) list.getSelectionModel().select(current.family());

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        Button ok = new Button("Use Font");
        ok.getStyleClass().add("primary-button");
        ok.setDefaultButton(true);
        ok.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

        BlockFont[] result = {null};
        Runnable choose = () -> {
            String family = list.getSelectionModel().getSelectedItem();
            if (family == null) return;
            result[0] = family.equals(BlockFont.NUNITO.family()) ? BlockFont.NUNITO : new BlockFont(family);
            stage.close();
        };
        ok.setOnAction(e -> choose.run());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) choose.run();
        });
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, cancel, ok);
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, search, list, bar);
        root.setPadding(new Insets(16));
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(ThemedWindows.scene(root, 420, 480));
        stage.setOnShown(e -> search.requestFocus());
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }
}
