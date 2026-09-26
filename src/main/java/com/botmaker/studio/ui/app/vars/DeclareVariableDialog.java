package com.botmaker.studio.ui.app.vars;

import com.botmaker.studio.palette.TypeNames;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.ui.render.components.types.TypeCatalog;
import com.botmaker.studio.ui.render.components.types.TypeChooser;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.Set;

/**
 * Declare Variable: a type from the type chooser and a name, asked before anything is written (2026-09-26).
 *
 * <p>It replaced five entries — Int, Double, Bool and String Variable, and Create List — each a fixed type, so a
 * variable of any other type was declared as one of those and retyped afterwards. The chooser offers every
 * type, wraps it in a List or a Map at any depth, and the name follows the type until the user types one.
 */
public final class DeclareVariableDialog {

    /** What the user asked for: the variable's name and its type. */
    public record Declared(String name, Type type) {}

    private DeclareVariableDialog() {}

    /** Asks, then writes the declaration into {@code body} at {@code index}. Nothing is written on Cancel. */
    public static void declareInto(com.botmaker.studio.services.CodeEditorService context, Window owner,
                                   com.botmaker.studio.core.BodyBlock body, int index) {
        BotRecords records = BotRecords.scan(context.getConfig(), context.getState(),
                com.botmaker.studio.plugin.PluginHost.grammar());
        ask(owner, records, EditVariableDialog.declaredNames(context)).ifPresent(declared ->
                context.getCodeEditor().declareLocal(body, index, declared.name(), declared.type()));
    }

    /**
     * The variable the user described, or empty when they cancelled. {@code taken} is every name the file
     * already declares, so the suggested name is free and a typed one that is not is refused.
     */
    public static Optional<Declared> ask(Window owner, BotRecords records, Set<String> taken) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Declare Variable");

        TypeChooser type = new TypeChooser(() -> TypeCatalog.current(TypeCatalog.Purpose.DECLARATION, records));
        type.setType(int.class);
        TextField name = new TextField(free(TypeNames.variableName(int.class), taken));
        name.setPromptText("its name");
        HBox.setHgrow(name, Priority.ALWAYS);

        // The name follows the type until the user types one of their own.
        boolean[] typed = {false};
        boolean[] suggesting = {false};
        name.textProperty().addListener((o, was, now) -> {
            if (!suggesting[0]) typed[0] = true;
        });
        type.typeProperty().addListener((o, was, now) -> {
            if (now == null || typed[0]) return;
            suggesting[0] = true;
            name.setText(free(TypeNames.variableName(now), taken));
            suggesting[0] = false;
        });

        Label problem = new Label();
        problem.getStyleClass().add("variables-rename-error");
        problem.setWrapText(true);

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        Button ok = new Button("Declare");
        ok.getStyleClass().add("primary-button");
        ok.setDefaultButton(true);

        Runnable validate = () -> {
            String why = problem(name.getText(), taken);
            problem.setText(why == null ? "" : why);
            problem.setVisible(why != null);
            problem.setManaged(why != null);
            ok.setDisable(why != null || type.type() == null);
        };
        name.textProperty().addListener((o, was, now) -> validate.run());
        type.typeProperty().addListener((o, was, now) -> validate.run());
        validate.run();

        Declared[] result = {null};
        ok.setOnAction(e -> {
            result[0] = new Declared(name.getText().trim(), type.type());
            stage.close();
        });
        cancel.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, cancel, ok);
        bar.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, row("Type", type), row("Name", name), problem, bar);
        root.setPadding(new Insets(18));
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(ThemedWindows.scene(root, 460, 190));
        stage.setOnShown(e -> {
            name.requestFocus();
            name.selectAll();
        });
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    /** Why {@code name} cannot be declared here, or null when it can. */
    static String problem(String name, Set<String> taken) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return "Give the variable a name.";
        if (!SourceVersion.isName(trimmed)) return "“" + trimmed + "” is not a name Java accepts.";
        if (taken.contains(trimmed)) return "“" + trimmed + "” is already declared in this file.";
        return null;
    }

    /** {@code base}, or {@code base2}, {@code base3}… — the first the file does not declare. */
    static String free(String base, Set<String> taken) {
        if (!taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            if (!taken.contains(base + i)) return base + i;
        }
    }

    private static HBox row(String label, Node field) {
        Label caption = new Label(label);
        caption.setMinWidth(60);
        HBox row = new HBox(10, caption, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
