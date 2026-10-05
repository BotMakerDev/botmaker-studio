package com.botmaker.studio.ui.app.trial;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.trial.TrialCaller;
import com.botmaker.studio.services.trial.TrialPlan;
import com.botmaker.studio.ui.app.params.ValueEditors;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ▶ Try's question: where each earlier local the statement reads gets its value — the last run, computed again,
 * or given here with the type's own editor, the one the Parameters window and a slot draw. Only the locals the
 * chosen sources need are listed; choosing to compute one that reads another adds that one.
 */
final class TryDialog {

    private final TrialPlan.Plan plan;
    private final ProjectConfig config;
    private final Map<String, TrialPlan.Source> chosen = new HashMap<>();
    /** The editor shown for each local asked for, kept while the dialog is open so a value survives a redraw. */
    private final Map<String, ValueEditors.Editor> editors = new HashMap<>();
    private final GridPane grid = new GridPane();
    private final Label problem = new Label();

    private TryDialog(TrialPlan.Plan plan, ProjectConfig config) {
        this.plan = plan;
        this.config = config;
    }

    /** The values to try {@code plan} with, or empty when cancelled. FX thread. */
    static Optional<Map<String, TrialCaller.Value>> ask(Window owner, TrialPlan.Plan plan, ProjectConfig config) {
        return new TryDialog(plan, config).show(owner);
    }

    private Optional<Map<String, TrialCaller.Value>> show(Window owner) {
        Dialog<Map<String, TrialCaller.Value>> dialog = new Dialog<>();
        dialog.setTitle("Try");
        dialog.setHeaderText("▶ Try " + plan.label() + "\nIt reads these earlier values. Where does each come from?");
        if (owner != null) dialog.initOwner(owner);
        ButtonType run = new ButtonType("▶ Try", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(run, ButtonType.CANCEL);
        grid.setHgap(10);
        grid.setVgap(8);
        problem.getStyleClass().add("try-problem");
        problem.setWrapText(true);
        VBox content = new VBox(10, grid, problem);
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        redraw();

        Node runButton = dialog.getDialogPane().lookupButton(run);
        runButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (values().isEmpty()) e.consume();   // the problem line says which value is missing
        });
        dialog.setResultConverter(button -> button == run ? values().orElse(null) : null);
        ThemedWindows.apply(dialog);
        return dialog.showAndWait();
    }

    /** One row per needed local: its name and type, where its value comes from, and that value. */
    private void redraw() {
        grid.getChildren().clear();
        problem.setText("");
        int row = 0;
        for (TrialPlan.Local local : plan.needed(chosen)) {
            Label name = new Label(local.name());
            name.setTooltip(new javafx.scene.control.Tooltip(local.type()));
            ChoiceBox<TrialPlan.Source> source = new ChoiceBox<>();
            source.getItems().setAll(local.sources());
            source.setConverter(new StringConverter<>() {
                @Override
                public String toString(TrialPlan.Source s) {
                    return s == null ? "" : s.displayName();
                }

                @Override
                public TrialPlan.Source fromString(String s) {
                    return null;
                }
            });
            source.setValue(chosen.getOrDefault(local.name(), local.defaultSource()));
            source.setOnAction(e -> {
                chosen.put(local.name(), source.getValue());
                redraw();
            });
            grid.addRow(row++, name, source, valueNode(local, source.getValue()));
        }
    }

    private Node valueNode(TrialPlan.Local local, TrialPlan.Source source) {
        return switch (source) {
            case LAST_RUN -> dim(local.lastRun().source());
            case COMPUTE -> dim(local.initializer());
            case ASK -> editors.computeIfAbsent(local.name(), n ->
                    ValueEditors.editorFor(form(local), null, ValueEditors.Context.of(config))).node();
        };
    }

    private static Label dim(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("try-source");
        label.setMaxWidth(360);
        return label;
    }

    private static Type form(TrialPlan.Local local) {
        return PluginHost.grammar().named(local.typeName()).map(c -> (Type) c)
                .orElseGet(() -> new ValueTypes.Unknown(local.typeName()));
    }

    /** Every needed local's value; empty, with the problem line saying which, when one asked for has none. */
    private Optional<Map<String, TrialCaller.Value>> values() {
        Map<String, TrialCaller.Value> out = new LinkedHashMap<>();
        List<TrialPlan.Local> needed = plan.needed(chosen);
        for (TrialPlan.Local local : needed) {
            TrialPlan.Source source = chosen.getOrDefault(local.name(), local.defaultSource());
            switch (source) {
                case LAST_RUN -> out.put(local.name(), new TrialCaller.Given(local.lastRun()));
                case COMPUTE -> out.put(local.name(), new TrialCaller.Computed());
                case ASK -> {
                    ValueEditors.Editor editor = editors.get(local.name());
                    Optional<JavaValue> value = editor == null ? Optional.empty() : editor.read().get();
                    if (value.isEmpty()) {
                        problem.setText("Give " + local.name() + " a value, or take it from somewhere else. Studio "
                                + "can only ask for a value its editors can write.");
                        return Optional.empty();
                    }
                    out.put(local.name(), new TrialCaller.Given(value.get()));
                }
            }
        }
        return Optional.of(out);
    }
}
