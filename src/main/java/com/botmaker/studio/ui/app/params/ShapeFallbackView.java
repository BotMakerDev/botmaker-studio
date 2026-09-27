package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The JavaFX half of {@link FallbackShape}: a dropdown, a field, one row per part, or the source kept with
 * Reset. Each editor reads back empty until the person changes something, which is what keeps an untouched value
 * byte-identical.
 */
final class ShapeFallbackView {

    private ShapeFallbackView() {}

    static ValueEditors.Editor editor(ValueGrammar grammar, Type leaf, String source, ValueEditors.Context ctx,
                                      Consumer<JavaValue> onChange, int depth) {
        return switch (FallbackShape.of(grammar, leaf, source, depth)) {
            case FallbackShape.Choice choice -> choice(grammar, leaf, choice, onChange);
            case FallbackShape.Literal literal -> literal(grammar, leaf, literal, onChange);
            case FallbackShape.Parts parts -> parts(grammar, leaf, parts, ctx, onChange, depth);
            case FallbackShape.Kept kept -> kept(grammar, leaf, kept, onChange);
        };
    }

    private static ValueEditors.Editor choice(ValueGrammar grammar, Type leaf, FallbackShape.Choice choice,
                                              Consumer<JavaValue> onChange) {
        ComboBox<Object> box = new ComboBox<>();
        box.getItems().setAll(choice.constants());
        choice.held().ifPresent(box::setValue);
        Holder current = new Holder();
        box.valueProperty().addListener((o, was, now) -> {
            if (now == null || now.equals(was)) return;
            current.set(FallbackShape.write(grammar, leaf, now), onChange);
        });
        return new ValueEditors.Editor(box, current::get);
    }

    private static ValueEditors.Editor literal(ValueGrammar grammar, Type leaf, FallbackShape.Literal literal,
                                               Consumer<JavaValue> onChange) {
        TextField field = new TextField(literal.shown());
        Holder current = new Holder();
        Runnable commit = () -> FallbackShape.literal(literal.type(), field.getText())
                .ifPresent(value -> current.set(FallbackShape.write(grammar, leaf, value), onChange));
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) commit.run();
        });
        return new ValueEditors.Editor(field, current::get);
    }

    private static ValueEditors.Editor parts(ValueGrammar grammar, Type leaf, FallbackShape.Parts parts,
                                             ValueEditors.Context ctx, Consumer<JavaValue> onChange, int depth) {
        VBox column = new VBox(4);
        List<ValueEditors.Editor> editors = new ArrayList<>();
        Holder current = new Holder();
        Runnable rebuild = () -> {
            List<Object> values = new ArrayList<>(parts.values());
            for (int i = 0; i < editors.size(); i++) {
                Class<?> type = parts.types().get(i);
                int at = i;
                editors.get(i).read().get()
                        .flatMap(written -> grammar.valueOf(type, written.source()))
                        .ifPresent(value -> values.set(at, value));
            }
            current.set(FallbackShape.rebuild(grammar, leaf, parts, values), onChange);
        };
        for (int i = 0; i < parts.types().size(); i++) {
            Class<?> type = parts.types().get(i);
            String partSource = FallbackShape.write(grammar, type, parts.values().get(i))
                    .map(JavaValue::source).orElse(null);
            ValueEditors.Editor part = ValueEditors.editorFor(type, partSource, ctx, changed -> rebuild.run(),
                    depth + 1);
            editors.add(part);
            Label name = new Label(parts.labels().get(i));
            name.getStyleClass().add("dialog-hint-text");
            name.setMinWidth(72);
            HBox line = new HBox(6, name, part.node());
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(part.node(), Priority.ALWAYS);
            column.getChildren().add(line);
        }
        return new ValueEditors.Editor(column, () -> {
            // A part with no onChange of its own still counts: ask it once more when the row is read.
            if (current.get().isEmpty() && editors.stream().anyMatch(e -> e.read().get().isPresent())) rebuild.run();
            return current.get();
        });
    }

    private static ValueEditors.Editor kept(ValueGrammar grammar, Type leaf, FallbackShape.Kept kept,
                                            Consumer<JavaValue> onChange) {
        TextField field = new TextField(kept.source());
        field.setEditable(false);
        field.setTooltip(new Tooltip(grammar.known(leaf)
                ? "No installed plugin draws an editor for " + ValueTypes.sourceName(leaf) + ". It is kept as written."
                : "No installed plugin declares " + ValueTypes.sourceName(leaf) + ". It is kept as written."));
        if (kept.fresh().isEmpty()) return new ValueEditors.Editor(field, Optional::empty);
        Holder current = new Holder();
        Button reset = new Button("Reset");
        reset.setTooltip(new Tooltip("Replace it with a fresh value"));
        reset.setOnAction(e -> {
            current.set(kept.fresh(), onChange);
            field.setText(kept.fresh().get().source());
        });
        HBox row = new HBox(6, field, reset);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return new ValueEditors.Editor(row, current::get);
    }

    /** What an editor wrote so far — empty until the person changes something. */
    private static final class Holder {
        private Optional<JavaValue> value = Optional.empty();

        Optional<JavaValue> get() {
            return value;
        }

        void set(Optional<JavaValue> next, Consumer<JavaValue> onChange) {
            if (next.isEmpty()) return;
            value = next;
            if (onChange != null) onChange.accept(next.get());
        }
    }
}
