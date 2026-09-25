package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.render.theme.BlockFont;
import com.botmaker.studio.ui.render.theme.ImportedFonts;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * View ▸ Block Font ▸ More Fonts…: the faces Studio ships, each as a line of block text drawn the way the
 * canvas would draw it, then every family on this machine in a list a search narrows, and <em>Import font…</em>
 * for a file the machine has not installed ({@link ImportedFonts}). Choosing one is reading it.
 */
final class BlockFontDialog {

    private static final String KEYWORD = "repeat";
    private static final String SAMPLE = "10 times  ·  if count > 3";

    private BlockFontDialog() {}

    /** The font chosen, or empty when the user cancelled. */
    static Optional<BlockFont> ask(Window owner, BlockFont current) {
        BlockFont.loadBundled();
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Block Font");

        ObjectProperty<BlockFont> chosen = new SimpleObjectProperty<>();
        boolean[] accepted = {false};
        Runnable accept = () -> {
            if (chosen.get() == null) return;
            accepted[0] = true;
            stage.close();
        };

        ToggleGroup group = new ToggleGroup();
        VBox bundled = new VBox(4);
        for (BlockFont.Bundled face : BlockFont.Bundled.values()) {
            ToggleButton row = new ToggleButton();
            row.setToggleGroup(group);
            row.setUserData(face.font());
            row.setGraphic(bundledRow(face));
            row.setMaxWidth(Double.MAX_VALUE);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("block-font-bundled");
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    group.selectToggle(row);
                    accept.run();
                }
            });
            bundled.getChildren().add(row);
        }

        TextField search = new TextField();
        search.setPromptText("Search installed fonts…");
        ObservableList<String> installed = FXCollections.observableArrayList(
                Font.getFamilies().stream().filter(f -> !isBundled(f)).toList());
        FilteredList<String> families = new FilteredList<>(installed);
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
                Label sample = new Label(KEYWORD + "  " + SAMPLE);
                sample.setFont(Font.font(family, 14));
                setText(null);
                setGraphic(new VBox(1, name, sample));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        // One choice across both halves: picking a bundled face clears the list, and the other way round.
        group.selectedToggleProperty().addListener((obs, was, toggle) -> {
            if (toggle == null) return;
            list.getSelectionModel().clearSelection();
            chosen.set((BlockFont) toggle.getUserData());
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, was, family) -> {
            if (family == null) return;
            group.selectToggle(null);
            chosen.set(new BlockFont(family));
        });
        current.asBundled().ifPresentOrElse(
                face -> group.getToggles().stream()
                        .filter(t -> face.font().equals(t.getUserData()))
                        .findFirst().ifPresent(group::selectToggle),
                () -> {
                    if (current.family() != null) list.getSelectionModel().select(current.family());
                });

        Label problem = new Label();
        problem.getStyleClass().add("block-font-problem");
        problem.setWrapText(true);
        problem.managedProperty().bind(problem.textProperty().isNotEmpty());
        problem.visibleProperty().bind(problem.managedProperty());

        Button importFont = new Button("Import font…");
        importFont.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Import a font");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Fonts", "*.ttf", "*.otf"));
            File file = chooser.showOpenDialog(stage);
            if (file == null) return;
            try {
                BlockFont font = ImportedFonts.importFont(file.toPath());
                problem.setText("");
                if (!installed.contains(font.family())) installed.add(font.family());
                search.clear();
                list.getSelectionModel().select(font.family());
                list.scrollTo(font.family());
            } catch (IOException ex) {
                problem.setText(ex.getMessage());
            }
        });

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        Button ok = new Button("Use Font");
        ok.getStyleClass().add("primary-button");
        ok.setDefaultButton(true);
        ok.disableProperty().bind(chosen.isNull());

        ok.setOnAction(e -> accept.run());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) accept.run();
        });
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, importFont, spacer, cancel, ok);
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10,
                section("BotMaker fonts"), bundled,
                section("Installed on this computer"), search, list,
                problem, bar);
        root.setPadding(new Insets(16));
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(ThemedWindows.scene(root, 460, 640));
        stage.showAndWait();
        return accepted[0] ? Optional.ofNullable(chosen.get()) : Optional.empty();
    }

    /** A bundled face as the canvas draws it: the keyword bold, the rest in the face's label cut. */
    private static VBox bundledRow(BlockFont.Bundled face) {
        Label name = new Label(face.family() + "  ·  " + face.note());
        name.getStyleClass().add("block-font-name");
        Label keyword = new Label(KEYWORD);
        keyword.setFont(face == BlockFont.Bundled.NUNITO
                ? Font.font("Nunito ExtraBold", 15)
                : Font.font(face.family(), FontWeight.BOLD, 15));
        Label rest = new Label(SAMPLE);
        rest.setFont(Font.font(face.labelFamily(), 15));
        HBox sample = new HBox(6, keyword, rest);
        sample.setAlignment(Pos.BASELINE_LEFT);
        return new VBox(1, name, sample);
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("block-font-section");
        return label;
    }

    /** A family one of the bundled faces registered: shown above, so not repeated in the installed list. */
    private static boolean isBundled(String family) {
        for (BlockFont.Bundled face : BlockFont.Bundled.values()) {
            if (family.equals(face.family()) || family.startsWith(face.family() + " ")) return true;
        }
        return false;
    }
}
