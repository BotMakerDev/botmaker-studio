package com.botmaker.studio.ui.app.vars;

import com.botmaker.studio.palette.EnumDraft;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Optional;
import java.util.Set;

/**
 * Asks for an enum's name and values before Define Enum is inserted. The palette wrote
 * {@code enum MyEnum { OPTION_A, OPTION_B }} and left both to be found on the block afterwards; a second
 * insert then declared a second {@code MyEnum}. Here the name and the values are the first thing asked, checked
 * as they are typed ({@link EnumDraft#problem}), and OK stays disabled with the reason shown until they are
 * right. Enter confirms, Esc cancels.
 */
public final class DefineEnumDialog {

    private DefineEnumDialog() {}

    /**
     * The enum the user described, or empty when they cancelled. {@code suggested} prefills the name;
     * {@code takenTypes} is every type name the file already declares.
     */
    public static Optional<EnumDraft> ask(Window owner, String suggested, Set<String> takenTypes) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Define Enum");

        TextField name = new TextField(suggested);
        name.setPromptText("what it names — Direction");
        TextField values = new TextField(String.join(", ", EnumDraft.DEFAULT_CONSTANTS));
        values.setPromptText("its values, separated by commas — UP, DOWN");
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox.setHgrow(values, Priority.ALWAYS);

        Label problem = new Label();
        problem.getStyleClass().add("variables-rename-error");
        problem.setWrapText(true);

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        Button ok = new Button("Insert");
        ok.getStyleClass().add("primary-button");
        ok.setDefaultButton(true);

        EnumDraft[] result = {null};
        Runnable validate = () -> {
            Optional<String> why = draft(name, values).problem(takenTypes);
            problem.setText(why.orElse(""));
            problem.setVisible(why.isPresent());
            problem.setManaged(why.isPresent());
            ok.setDisable(why.isPresent());
        };
        name.textProperty().addListener((obs, was, now) -> validate.run());
        values.textProperty().addListener((obs, was, now) -> validate.run());
        validate.run();

        ok.setOnAction(e -> {
            result[0] = draft(name, values);
            stage.close();
        });
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, cancel, ok);
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, row("Name", name), row("Values", values), problem, bar);
        root.setPadding(new Insets(18));
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(ThemedWindows.scene(root, 460, 200));
        stage.setOnShown(e -> {
            name.requestFocus();
            name.selectAll();
        });
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    private static EnumDraft draft(TextField name, TextField values) {
        return new EnumDraft(name.getText(), EnumDraft.constantsOf(values.getText()));
    }

    private static HBox row(String label, TextField field) {
        Label caption = new Label(label);
        caption.setMinWidth(60);
        HBox row = new HBox(10, caption, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
