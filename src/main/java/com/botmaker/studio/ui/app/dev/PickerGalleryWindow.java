package com.botmaker.studio.ui.app.dev;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every value editor in the app, on one screen, with what each one currently reads back.
 *
 * <p><b>Why a screen and not a test.</b> The failures these editors actually have are not the ones an
 * assertion catches: a spinner that refuses a typed {@code 1.5}, a combo whose comment says it is editable
 * when it is not, a thumbnail that never appears because a relative path was resolved against the working
 * directory. Every one of them is a thing you see. What was missing was a place to see all of them at once,
 * without declaring a variable of each type in a real project first.
 *
 * <p><b>The readout is the point.</b> Each row shows the control on the left and, on the right, the Java it
 * reads back <em>right now</em> — polled, so it follows the control as it is touched — and beneath it whether
 * the host's grammar reads that Java back. A picker that looks right and hands back {@code ""}, or one that
 * writes Java nothing can read, is invisible without those two lines side by side.
 *
 * <p>Rows are built through {@link ParamValueWidgets#build}, not by calling
 * {@link com.botmaker.studio.ui.app.params.ValueEditors#editorFor} directly, so what is on screen is exactly
 * what the Parameters dialog and the Runner window put there — shape widgets included. A row whose
 * construction throws says so in place of its control rather than taking the window down with it: finding
 * that is why you opened this.
 *
 * <p><b>Dev builds only.</b> Gated by {@link com.botmaker.studio.config.AppVersion#isDevBuild()} where it is
 * offered (Help ▸ Picker Gallery), the same switch that decides whether locally-installed SDK snapshots are
 * listed. It is Java source, but it is a screen, not a test class.
 */
public final class PickerGalleryWindow {

    /** How often the readouts re-read their controls. Fast enough to feel live, idle enough to ignore. */
    private static final Duration PULSE = Duration.millis(200);

    private final Window owner;
    private final ProjectConfig project;

    private final TextField filter = new TextField();
    private final CheckBox shapesToo = new CheckBox("Show the list and choice shapes too");
    private final GridPane grid = new GridPane();
    private final Label summary = new Label();
    private final List<Row> rows = new ArrayList<>();

    private Timeline pulse;

    /** @param project the open project, so the template and colour editors have something to resolve; may be null */
    public PickerGalleryWindow(Window owner, ProjectConfig project) {
        this.owner = owner;
        this.project = project;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Picker Gallery (dev)");

        filter.setPromptText("Filter by type — \"date\", \"number\", \"template\"…");
        filter.textProperty().addListener((obs, was, now) -> rebuild());
        shapesToo.setSelected(true);
        shapesToo.selectedProperty().addListener((obs, was, now) -> rebuild());

        grid.setHgap(14);
        grid.setVgap(10);
        grid.setPadding(new Insets(4, 4, 12, 4));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(150);
        ColumnConstraints shape = new ColumnConstraints();
        shape.setMinWidth(90);
        ColumnConstraints control = new ColumnConstraints();
        control.setMinWidth(280);
        control.setHgrow(Priority.SOMETIMES);
        ColumnConstraints readout = new ColumnConstraints();
        readout.setMinWidth(220);
        readout.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, shape, control, readout);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);

        summary.getStyleClass().add("dialog-hint-text");

        VBox root = new VBox(10, header(), new Separator(), scroll, summary);
        root.setPadding(new Insets(16));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        rebuild();

        // Nothing here listens to the controls: they are twenty different widget types with twenty different
        // "changed" signals, and a poll asks all of them the one question this screen cares about.
        pulse = new Timeline(new KeyFrame(PULSE, e -> rows.forEach(Row::refresh)));
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
        stage.setOnHidden(e -> pulse.stop());

        stage.setScene(ThemedWindows.scene(root, 1080, 760));
        stage.show();
    }

    private Node header() {
        Label title = new Label("Every editor, and what it hands back");
        title.getStyleClass().add("dialog-heading");
        Label note = new Label(project == null
                ? "No project open — the template and colour editors have nothing to resolve against."
                : "Resolving templates and colours against " + project.projectName() + ".");
        note.getStyleClass().add("dialog-hint-text");
        HBox controls = new HBox(12, filter, shapesToo);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filter, Priority.ALWAYS);
        return new VBox(6, title, note, controls);
    }

    // --- the rows -------------------------------------------------------------------------------------------

    /**
     * One row per (type, form) the plugins bound right now can express.
     *
     * <p>The set comes from the declared types ({@code StudioPlugin.types()}) rather than from a constant
     * list: a stored variable's type is whatever a plugin declares, so a screen enumerating a fixed set would
     * stop showing the editors it is for the moment a second plugin adds one.
     *
     * <p>The second axis is a <b>form</b> since 2026-09-20 and no longer a {@code ValueShape}: a list of a
     * type and a map from text to it are two more cells to look at, and whether a set of choices is declared
     * is a fact about the row rather than about its type.
     */
    private void rebuild() {
        rows.clear();
        grid.getChildren().clear();

        String needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        int line = 0;
        for (String type : declaredTypes()) {
            if (!needle.isEmpty() && !type.toLowerCase(Locale.ROOT).contains(needle)) continue;
            for (Shape shape : SHAPES) {
                if (!shape.plain() && !shapesToo.isSelected()) continue;
                Sample variable = sample(type, shape);
                if (variable == null) continue;   // this sample is not a sentence for this type
                line = addRow(variable, type, shape, line);
            }
        }
        summary.setText(rows.size() + " editors shown. The right-hand column is polled every "
                + (int) PULSE.toMillis() + "ms: touch a control and watch it move.");
    }

    /** Every declared type's canonical name, in plugin order. */
    static List<String> declaredTypes() {
        List<String> out = new ArrayList<>();
        for (PluginType<?> type : PluginHost.grammar().types()) {
            try {
                out.add(JavaNames.canonical(type.type()));
            } catch (RuntimeException | LinkageError e) {
                // A plugin that cannot name its own type contributes no row, and costs nobody else theirs.
            }
        }
        return out;
    }

    private int addRow(Sample variable, String type, Shape shape, int line) {
        Label name = new Label(JavaNames.simple(type));
        Label shapeName = new Label(shape.label());
        shapeName.getStyleClass().add("dialog-hint-text");

        List<ParamValueWidgets.ValueEditor> readers = new ArrayList<>();
        Node widget;
        try {
            widget = ParamValueWidgets.build("", variable.row(), variable.form(), project, readers);
        } catch (RuntimeException | Error e) {
            // The whole reason for the screen: an editor that cannot even be built is a finding, not a crash.
            widget = broken("built: " + e);
            readers.clear();
        }

        Label raw = new Label();
        raw.getStyleClass().add("picker-wire-readout");
        raw.setWrapText(true);
        Label stored = new Label();
        stored.getStyleClass().addAll("picker-wire-readout", "dialog-hint-text");
        stored.setWrapText(true);
        VBox readout = new VBox(2, raw, stored);

        grid.addRow(line, name, shapeName, widget, readout);
        // A tick list is eight rows tall and its label belongs beside the first of them, not halfway down it.
        for (Node cell : List.of(name, shapeName, widget, readout)) GridPane.setValignment(cell, VPos.TOP);
        rows.add(new Row(variable, readers, raw, stored));
        return line + 1;
    }

    private static Node broken(String message) {
        Label label = new Label("✕ " + message);
        label.setWrapText(true);
        label.getStyleClass().add("dialog-error-text");
        return label;
    }

    /** One sample parameter: the row and the form it is of. */
    record Sample(ParameterRow row, Type form) {}

    /** One row: the row it was built from, its widget's readers, and the two lines they write to. */
    private record Row(Sample variable, List<ParamValueWidgets.ValueEditor> readers,
                       Label raw, Label stored) {

        void refresh() {
            if (readers.isEmpty()) {
                write("—", "");
                return;
            }
            String read;
            try {
                read = readers.getFirst().read().get().map(JavaValue::source).orElse("");
            } catch (RuntimeException | Error e) {
                write("✕ read: " + e, "");
                return;
            }
            String back;
            try {
                back = readsBack(variable, read);
            } catch (RuntimeException | Error e) {
                back = "✕ reading back: " + e;
            }
            write(read.isBlank() ? "— nothing written" : read, back);
        }

        private void write(String rawText, String storedText) {
            if (!rawText.equals(raw.getText())) raw.setText(rawText);
            if (!storedText.equals(stored.getText())) stored.setText(storedText);
        }

        /**
         * Whether the grammar reads back what the cell just wrote — the failure this screen exists for, now
         * that a value is Java rather than wire text. A cell that writes source nothing can parse is invisible
         * until a project is reopened, and this line says so while the control is still being touched.
         */
        private static String readsBack(Sample sample, String source) {
            if (source.isBlank()) return "";
            return PluginHost.grammar().valueOf(sample.form(), source)
                    .map(value -> "reads back as " + value)
                    .orElse("✕ nothing reads this back");
        }
    }

    // --- the sample variable each row is built from ----------------------------------------------------------

    /**
     * One sample form to draw a type in: what the second column calls it, how the leaf is wrapped, and
     * whether the row declares a set of choices.
     *
     * @param plain the one sample always shown — the free value, which is what the filter box is usually for
     */
    record Shape(String label, String suffix, boolean list, boolean map, boolean options, boolean plain) {

        /** {@code type} — a canonical name, looked up exactly — wrapped the way this shape wraps it. */
        Type formOf(String type) {
            Type leaf = PluginHost.grammar().named(type).<Type>map(cls -> cls).orElse(new ValueTypes.Unknown(type));
            if (list) return ValueTypes.listOf(leaf);
            if (map) return ValueTypes.mapOf(String.class, leaf);
            return leaf;
        }
    }

    /**
     * The five cells a type can be drawn in, which is the whole of {@link ParamValueWidgets}'s dispatch.
     *
     * <p>A map is keyed by text and never by the type being shown: the screen is about the <em>value</em>
     * editor, and a map of colours to colours would draw the same cell twice in one row.
     */
    static final List<Shape> SHAPES = List.of(
            new Shape("free value", "", false, false, false, true),
            new Shape("one of…", "OneOf", false, false, true, false),
            new Shape("list of…", "List", true, false, false, false),
            new Shape("any of…", "AnyOf", true, false, true, false),
            new Shape("map of…", "Map", false, true, false, false));

    /**
     * A row of {@code type} in {@code shape}, or null when that pairing is not a thing anyone can declare —
     * or not one this screen has choices to fill in for.
     */
    static Sample sample(String type, Shape shape) {
        List<String> options = shape.options() ? options(type) : List.of();
        if (shape.options() && options.isEmpty()) return null;
        Type form = shape.formOf(type);
        ValueGrammar grammar = PluginHost.grammar();
        ParameterRow row = ParameterRow.named(identifier(type, shape), ValueTypes.sourceName(form))
                .value(grammar.freshInitializer(form).map(JavaValue::source).orElse(""))
                .visibility(Visibility.PUBLIC)
                .options(options)
                .build();
        return new Sample(row, form);
    }

    /**
     * A valid Java identifier, because a parameter's name is a field's — nothing here writes code, and a
     * sample that could not have been declared for real is a poor sample.
     */
    private static String identifier(String type, Shape shape) {
        String simple = JavaNames.simple(type).replaceAll("[^A-Za-z0-9_]", "_");
        if (simple.isEmpty() || Character.isDigit(simple.charAt(0))) simple = "v" + simple;
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + shape.suffix();
    }

    /**
     * Two or three declared choices of {@code type}, for the shapes that need a set to draw — written as an
     * author writes them into {@code @Param(options = …)}.
     *
     * <p>Written out rather than derived: the values have to be <em>different from each other</em> and
     * different from the fresh one, or a row of three radio buttons all reading {@code 0} tests nothing. Only
     * the JDK's literals have samples here: they are the only types whose values this screen can spell
     * without knowing a plugin's vocabulary, which is the thing it is not allowed to know.
     */
    static List<String> options(String type) {
        return switch (type) {
            case "java.lang.String" -> List.of("first", "second", "third");
            case "int", "java.lang.Integer" -> List.of("1", "2", "3");
            case "double", "java.lang.Double" -> List.of("0.5", "1.5", "2.5");
            case "char", "java.lang.Character" -> List.of("'a'", "'b'", "'c'");
            default -> List.of();
        };
    }
}
