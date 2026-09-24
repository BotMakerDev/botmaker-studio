package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.ValueWire;
import com.botmaker.studio.plugin.grammar.JdkLiterals;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.params.BotRecords;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds the value-entry widget for one {@link ParameterRow}, seeded from the Java its value is written as,
 * and hands back a reader turning the widget's live state back into Java.
 *
 * <p><b>Source in, source out (2026-09-20).</b> A row's value is the initialiser its field takes, because a
 * composite has no other canonical form — so a cell reads and writes that spelling and nothing else. A leaf is
 * a plugin's editor over that leaf's own Java; a composite is taken apart one level at a time into parts that
 * are themselves source, edited, and composed back through its container's own factory.
 *
 * <p><b>There is no second spelling of a leaf any more (2026-09-22).</b> A leaf's control used to hold a
 * "wire" text and this class translated at the boundary — {@code ValueWire.wire} in, {@code literal} out —
 * which round-tripped every value through a decode that could not be checked and rewrote a
 * {@code java.awt.Color} the moment the window opened. An editor is handed Java and writes a value; the
 * host's grammar spells it. A declared choice is written by its author as text and is read as a value of the
 * leaf's type — {@code 10} for an {@code int}, {@code UP} for a {@code Direction}, {@code Mining} for a
 * {@code String} — so the choice a radio button stands for is its Java, not its label.
 *
 * <p><b>Reading is total and never validates.</b> Nothing here can refuse a value, so nothing here can leave
 * a window unable to close. The one refusal is a duplicate map key, and it happens <em>at the cell</em>:
 * {@code Map.ofEntries} throws on a repeated key, so a map that wrote one would be a bot that stops running.
 *
 * <p><b>The form decides the widget, and it decides it unconditionally.</b> A leaf is its type's own editor,
 * a list is rows, a map is two columns, a record the bot declares is one row per component. Anything else —
 * a nesting deeper than the cell draws, a leaf nothing declares — is shown <b>as the author wrote it</b> and
 * not edited. Read-only is a first-class outcome here ({@code docs/refactor/32-generic-values.md}), not a
 * failure path: the window never hides a parameter the bot reads.
 *
 * <p>Shared by the Parameters dialog and the Runner window, so a type is entered the same way wherever it is
 * met.
 */
public final class ParamValueWidgets {

    /** How wide a value column gets, so a row of them reads as a column rather than as ragged text. */
    private static final double VALUE_WIDTH = 260;

    private ParamValueWidgets() {}

    /**
     * A variable's handle plus a reader turning its widget's UI state back into the Java the field takes, and
     * the imports that Java needs.
     *
     * <p>A blank reading means <em>this widget has no source spelling for what is in it</em> — an empty radio
     * group, a leaf no editor could draw — and a caller writes nothing rather than guessing.
     */
    public record ValueEditor(String group, String name, Supplier<String> read, Supplier<List<String>> imports) {

        static ValueEditor of(String group, ParameterRow row, Supplier<String> read,
                              Supplier<List<String>> imports) {
            return new ValueEditor(group, row.name(), read, imports);
        }

        /** True when this reader was built from the row called {@code rowName} in {@code rowGroup}. */
        public boolean describes(String rowGroup, String rowName) {
            return name.equals(rowName) && group.equals(rowGroup == null ? "" : rowGroup);
        }
    }

    /**
     * The widget for one {@link ParameterRow} of {@code group}, of type {@code form}, seeded from its current
     * value.
     *
     * @param group  the section the row is filed under — half of the handle a reader is keyed by
     * @param config the project a plugin's editor builds its services for
     */
    public static Node build(String group, ParameterRow row, Type form, ProjectConfig config,
                             List<ValueEditor> sink) {
        return build(group, row, form, config, BotRecords.none(), sink);
    }

    /**
     * The same, told what the bot declares itself — which is what lets a field typed with one of the bot's
     * own records be edited component by component rather than shown as written.
     */
    public static Node build(String group, ParameterRow row, Type form, ProjectConfig config,
                             BotRecords records, List<ValueEditor> sink) {
        String owner = group == null ? "" : group;
        ValueEditors.Context ctx = ValueEditors.Context.of(config);
        Node widget = cell(owner, row, form == null ? ValueTypes.NONE : form,
                records == null ? BotRecords.none() : records, ctx, sink);
        widget.setId("param-value-" + row.name());
        return widget;
    }

    /** The same widget, pinned to one width — what a list of rows wants, and a form does not. */
    public static Node buildFixedWidth(String group, ParameterRow row, Type form, ProjectConfig config,
                                       List<ValueEditor> sink) {
        Node widget = build(group, row, form, config, sink);
        if (widget instanceof javafx.scene.layout.Region region) region.setPrefWidth(VALUE_WIDTH);
        return widget;
    }

    /** One case of {@link Type} per widget, and a read-only fallback for the rest. */
    private static Node cell(String group, ParameterRow row, Type form, BotRecords records,
                             ValueEditors.Context ctx, List<ValueEditor> sink) {
        ValueGrammar grammar = PluginHost.grammar();
        List<String> options = row.options();

        if (form instanceof ValueTypes.BotClass declared) {
            return records.whyNotEditable(declared) == null
                    ? recordRows(group, row, declared, records, ctx, sink)
                    : unreadable(row, records.whyNotEditable(declared));
        }
        // A leaf nothing declares is not sent down the read-only path: its own editor already draws it as a
        // disabled field holding the Java as written, and reads back blank, so nothing is written over it.
        if (ValueTypes.isLeaf(form)) {
            return options.isEmpty()
                    ? single(group, row, form, ctx, sink)
                    : radioRow(grammar, group, row, form, options, ctx, sink);
        }
        Optional<ValueContainer<?>> container = ValueTypes.container(form);
        List<Type> arguments = ValueTypes.arguments(form);
        if (container.orElse(null) == ValueContainer.LIST && ValueTypes.isLeaf(arguments.getLast())) {
            return options.isEmpty()
                    ? listRows(grammar, group, row, form, arguments.getLast(), ctx, sink)
                    : checkList(grammar, group, row, form, arguments.getLast(), options, ctx, sink);
        }
        if (container.orElse(null) == ValueContainer.MAP
                && ValueTypes.isLeaf(arguments.get(0)) && ValueTypes.isLeaf(arguments.get(1))) {
            return mapGrid(group, row, form, arguments.get(0), arguments.get(1), ctx, sink);
        }
        return unreadable(row, "no editor here writes " + ValueTypes.sourceName(form));
    }

    // --- the editable cells --------------------------------------------------------------------------------

    private static Node single(String group, ParameterRow row, Type leaf,
                               ValueEditors.Context ctx, List<ValueEditor> sink) {
        ValueEditors.Editor editor = ValueEditors.editorFor(leaf, row.value(), ctx);
        Node widget = editor.node();
        ValueEditors.stretch(widget);
        sink.add(ValueEditor.of(group, row, editor.read(), editor.imports()));
        return widget;
    }

    /**
     * One radio button per declared choice, at most one of them on.
     *
     * <p>Nothing selected is a legal state and the honest one: a stored value the author has since removed
     * from the list shows as no selection rather than as the first choice, which would be this widget
     * choosing a setting on the user's behalf. It reads back blank, and a blank reading is written nowhere.
     */
    private static Node radioRow(ValueGrammar grammar, String group, ParameterRow row, Type leaf,
                                 List<String> options, ValueEditors.Context ctx, List<ValueEditor> sink) {
        ToggleGroup toggles = new ToggleGroup();
        VBox column = new VBox(2);
        String current = canonical(grammar, leaf, row.value());
        for (String option : options) {
            Optional<ValueGrammar.Written> written = optionSource(grammar, leaf, option);
            RadioButton button = new RadioButton(option);
            button.setToggleGroup(toggles);
            button.setUserData(written.orElse(null));
            button.setDisable(written.isEmpty());
            written.ifPresent(w -> {
                button.setGraphic(ValueEditors.optionGraphic(leaf, w.source(), ctx));
                button.setSelected(w.source().equals(current));
            });
            column.getChildren().add(button);
        }
        if (options.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(group, row,
                () -> chosen(toggles).map(ValueGrammar.Written::source).orElse(""),
                () -> chosen(toggles).map(ValueGrammar.Written::imports).orElse(List.of())));
        return column;
    }

    private static Optional<ValueGrammar.Written> chosen(ToggleGroup toggles) {
        Toggle selected = toggles.getSelectedToggle();
        return selected != null && selected.getUserData() instanceof ValueGrammar.Written written
                ? Optional.of(written) : Optional.empty();
    }

    /** Declared choices, ticked — the list shape of {@link #radioRow}. */
    private static Node checkList(ValueGrammar grammar, String group, ParameterRow row, Type form,
                                  Type leaf, List<String> options, ValueEditors.Context ctx,
                                  List<ValueEditor> sink) {
        List<String> held = ValueWire.partsOrNone(form, row.value()).stream()
                .map(part -> canonical(grammar, leaf, part))
                .toList();
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(2);
        for (String option : options) {
            Optional<ValueGrammar.Written> written = optionSource(grammar, leaf, option);
            CheckBox box = new CheckBox(option);
            box.setUserData(written.orElse(null));
            box.setDisable(written.isEmpty());
            written.ifPresent(w -> {
                box.setGraphic(ValueEditors.optionGraphic(leaf, w.source(), ctx));
                box.setSelected(held.contains(w.source()));
            });
            boxes.add(box);
            column.getChildren().add(box);
        }
        if (boxes.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        Supplier<List<ValueGrammar.Written>> ticked = () -> boxes.stream()
                .filter(CheckBox::isSelected)
                .map(box -> box.getUserData() instanceof ValueGrammar.Written w ? w : null)
                .filter(w -> w != null)
                .toList();
        sink.add(ValueEditor.of(group, row,
                () -> ValueWire.compose(form, ticked.get().stream().map(ValueGrammar.Written::source).toList()),
                () -> importsOf(ticked.get().stream().map(ValueGrammar.Written::imports).toList())));
        return column;
    }

    /**
     * A list the user fills in themselves, out of no set at all.
     *
     * <p>Text is one item per line — a newline is not a character anybody types into a value by accident,
     * where a comma is, and twenty strings are faster typed than clicked. Every other type gets a growable
     * column of that type's own editor instead.
     */
    private static Node listRows(ValueGrammar grammar, String group, ParameterRow row, Type form,
                                 Type leaf, ValueEditors.Context ctx, List<ValueEditor> sink) {
        List<ValueGrammar.Part> parts = ValueWire.partsOrNone(form, row.value());
        if (leaf == String.class) {
            List<String> items = parts.stream()
                    .map(part -> grammar.valueOf(leaf, part.written()).orElse(null))
                    .filter(value -> value instanceof String)
                    .map(value -> (String) value)
                    .toList();
            TextArea area = new TextArea(String.join("\n", items));
            area.setPrefRowCount(Math.max(3, Math.min(8, items.size() + 1)));
            area.setPromptText("One per line");
            sink.add(ValueEditor.of(group, row, () -> ValueWire.compose(form, lines(area).stream()
                    .map(JdkLiterals::quote)
                    .toList()), List::of));
            return area;
        }

        List<ValueEditors.Editor> editors = new ArrayList<>();
        VBox column = new VBox(4);
        Button add = new Button("Add");
        Runnable[] rebuild = new Runnable[1];

        // The rows are rebuilt from the editors' own current state rather than from the row: this widget
        // outlives several adds and removes before anything is flushed back, so the row it was built from is
        // stale from the first click.
        rebuild[0] = () -> {
            column.getChildren().clear();
            for (int i = 0; i < editors.size(); i++) {
                ValueEditors.Editor editor = editors.get(i);
                int at = i;
                Button remove = new Button("✕");
                remove.getStyleClass().add("row-icon-button");
                remove.setOnAction(e -> {
                    editors.remove(at);
                    rebuild[0].run();
                });
                HBox line = new HBox(6, editor.node(), remove);
                line.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(editor.node(), Priority.ALWAYS);
                column.getChildren().add(line);
            }
            if (editors.isEmpty()) column.getChildren().add(hint("Nothing in this list yet."));
            column.getChildren().add(add);
        };

        for (ValueGrammar.Part part : parts) editors.add(ValueEditors.editorFor(leaf, part.source(), ctx));
        add.setOnAction(e -> {
            editors.add(ValueEditors.editorFor(leaf, null, ctx));
            rebuild[0].run();
        });
        rebuild[0].run();

        sink.add(ValueEditor.of(group, row,
                () -> ValueWire.compose(form, editors.stream()
                        .map(editor -> editor.read().get())
                        .filter(source -> !source.isBlank())
                        .toList()),
                () -> importsOf(editors.stream().map(editor -> editor.imports().get()).toList())));
        return column;
    }

    /**
     * A map as two columns of its own editors, one row per entry.
     *
     * <p><b>A duplicate key is refused at the cell it was typed into</b>, marked there the moment the field
     * is left, rather than reported when the window closes. {@code Map.ofEntries} throws on a repeated key,
     * so a map that wrote one would be a bot that stops running; the marked row is the one not written, and
     * it stays on screen holding what was typed, so nothing the user entered disappears silently.
     */
    private static Node mapGrid(String group, ParameterRow row, Type form, Type keyType,
                                Type valueType, ValueEditors.Context ctx, List<ValueEditor> sink) {
        Type entryForm = ValueTypes.container(form)
                .map(container -> container.partTypes(ValueTypes.arguments(form), 1).getFirst())
                .orElse(ValueTypes.NONE);

        List<Entry> entries = new ArrayList<>();
        for (ValueGrammar.Part part : ValueWire.partsOrNone(form, row.value())) {
            List<ValueGrammar.Part> pair = ValueWire.partsOrNone(part.form(), part.written());
            if (pair.size() != 2) continue;
            entries.add(new Entry(pair.get(0).source(), pair.get(1).source()));
        }

        List<Cell> cells = new ArrayList<>();
        VBox column = new VBox(4);
        Button add = new Button("Add");
        Runnable[] rebuild = new Runnable[1];

        rebuild[0] = () -> {
            column.getChildren().clear();
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                int at = i;
                Button remove = new Button("✕");
                remove.getStyleClass().add("row-icon-button");
                remove.setOnAction(e -> {
                    cells.remove(at);
                    rebuild[0].run();
                });
                HBox line = new HBox(6, cell.key().node(), new Label("→"), cell.value().node(), remove);
                line.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(cell.key().node(), Priority.ALWAYS);
                HBox.setHgrow(cell.value().node(), Priority.ALWAYS);
                column.getChildren().add(line);
            }
            if (cells.isEmpty()) column.getChildren().add(hint("Nothing in this map yet."));
            column.getChildren().add(add);
        };

        // The watcher is wired once per cell, where the cell is made: the rows are rebuilt on every add and
        // remove, so wiring it there would stack one listener per rebuild on the same field.
        for (Entry entry : entries) cells.add(watched(cells, keyType, valueType, entry, ctx));
        add.setOnAction(e -> {
            cells.add(watched(cells, keyType, valueType, new Entry(null, null), ctx));
            rebuild[0].run();
        });
        rebuild[0].run();

        sink.add(ValueEditor.of(group, row, () -> {
            List<String> written = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (Cell cell : cells) {
                String key = cell.key().read().get();
                String value = cell.value().read().get();
                // The duplicate is refused here as well as at the cell, because a key may be typed into a row
                // that is never focused out of. The first one written wins, which is the one on screen above.
                if (key.isBlank() || value.isBlank() || seen.contains(key)) continue;
                seen.add(key);
                written.add(ValueWire.compose(entryForm, List.of(key, value)));
            }
            return ValueWire.compose(form, written);
        }, () -> {
            List<List<String>> all = new ArrayList<>();
            for (Cell cell : cells) {
                all.add(cell.key().imports().get());
                all.add(cell.value().imports().get());
            }
            return importsOf(all);
        }));
        return column;
    }

    /**
     * A record the bot declares, as one labelled row per component.
     *
     * <p><b>The components are fixed, so there is no Add and no ✕.</b> A list and a map are as long as the
     * user makes them, and a record is exactly as long as its own declaration — changing that is editing the
     * record, which is a thing to do in the file and not in a parameters window.
     *
     * <p>A component whose own form is not a leaf keeps the source it already had, shown beside its name and
     * written back untouched.
     */
    private static Node recordRows(String group, ParameterRow row, ValueTypes.BotClass declared,
                                   BotRecords records, ValueEditors.Context ctx, List<ValueEditor> sink) {
        ValueGrammar grammar = PluginHost.grammar();
        List<BotRecords.Component> components = records.componentsOf(declared);
        List<ValueGrammar.Part> held = records.partsOf(declared, row.value()).orElse(List.of());

        VBox column = new VBox(4);
        List<ValueEditors.Editor> readers = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            BotRecords.Component component = components.get(i);
            String written = i < held.size() ? held.get(i).source() : "";
            Label name = new Label(component.name());
            name.getStyleClass().add("dialog-hint-text");
            name.setMinWidth(72);

            ValueEditors.Editor editor;
            if (ValueTypes.isLeaf(component.form()) && grammar.known(component.form())) {
                editor = ValueEditors.editorFor(component.form(), written, ctx);
            } else {
                Label shown = new Label(written.isBlank() ? "—" : written);
                shown.getStyleClass().add("dialog-hint-text");
                shown.setTooltip(new Tooltip("Kept as written: " + ValueTypes.sourceName(component.form())
                                             + " is edited where the record is."));
                editor = new ValueEditors.Editor(shown, () -> written, List::of);
            }
            readers.add(editor);

            HBox line = new HBox(6, name, editor.node());
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(editor.node(), Priority.ALWAYS);
            column.getChildren().add(line);
        }
        if (components.isEmpty()) column.getChildren().add(hint("This record has no components."));

        sink.add(ValueEditor.of(group, row,
                () -> records.initializerOfParts(declared,
                        readers.stream().map(editor -> editor.read().get()).toList()).orElse(""),
                () -> importsOf(readers.stream().map(editor -> editor.imports().get()).toList())));
        return column;
    }

    /** One entry's two halves as the Java they are written as. */
    private record Entry(String key, String value) {}

    /** One map row, with the key field watching for a key another row already has. */
    private static Cell watched(List<Cell> cells, Type keyType, Type valueType, Entry entry,
                                ValueEditors.Context ctx) {
        Cell cell = new Cell(ValueEditors.editorFor(keyType, entry.key(), ctx),
                ValueEditors.editorFor(valueType, entry.value(), ctx));
        cell.key().node().focusedProperty().addListener((o, was, is) -> {
            if (!is) markDuplicates(cells);
        });
        return cell;
    }

    /**
     * Marks every map row whose key a row above it already has.
     *
     * <p>Compared as the <b>source</b> each key is written as, which is what {@code Map.ofEntries} is handed:
     * two rows writing one key are one key, whatever either field displays.
     */
    private static void markDuplicates(List<Cell> cells) {
        List<String> seen = new ArrayList<>();
        for (Cell cell : cells) {
            String key = cell.key().read().get();
            boolean clash = !key.isBlank() && seen.contains(key);
            if (!clash && !key.isBlank()) seen.add(key);
            cell.key().node().getStyleClass().remove("field-error");
            if (clash) cell.key().node().getStyleClass().add("field-error");
            Tooltip.install(cell.key().node(), clash
                    ? new Tooltip("Another row already has this key, so this one is not written. "
                                  + "A map cannot hold one key twice.")
                    : null);
        }
    }

    /** One row of the map cell: the editor for its key and the editor for its value. */
    private record Cell(ValueEditors.Editor key, ValueEditors.Editor value) {}

    // --- the read-only fallback --------------------------------------------------------------------------

    /**
     * A value shown exactly as its author wrote it, with the reason it is not editable here.
     *
     * <p>The bot reads it either way, so hiding it would reproduce the fault the JSON era had: a declaration
     * the author cannot see. Nothing is added to {@code sink}, so nothing can be written back over it.
     */
    private static Node unreadable(ParameterRow row, String why) {
        Label written = new Label(row.value().isBlank() ? "—" : row.value());
        written.getStyleClass().add("dialog-hint-text");
        written.setTooltip(new Tooltip("Shown as written, not editable here: " + why + "."));
        return new VBox(2, written);
    }

    // --- declared choices ---------------------------------------------------------------------------------

    /**
     * A declared choice as the Java a field of {@code leaf} takes, or empty when the author's text is not a
     * value of that type.
     *
     * <p>The text is read as {@code leaf} first — {@code 10}, {@code UP}, {@code "Mining"} — and a
     * {@code String} leaf also takes the bare word, since {@code options = {"Mining"}} is how a person writes
     * a choice of text. Either way it is then written by the grammar, so a choice and a stored value that
     * mean the same thing are spelled the same and compare equal.
     */
    static Optional<ValueGrammar.Written> optionSource(ValueGrammar grammar, Type leaf, String option) {
        if (option == null || option.isBlank()) return Optional.empty();
        Optional<Object> value = grammar.valueOf(leaf, option);
        if (value.isEmpty() && leaf == String.class) {
            value = Optional.of(option);
        }
        return value.flatMap(v -> grammar.spell(leaf, v));
    }

    /** {@code source} as the grammar would write the value it holds — itself, when it cannot be read. */
    private static String canonical(ValueGrammar grammar, Type leaf, String source) {
        return grammar.valueOf(leaf, source)
                .flatMap(value -> grammar.spell(leaf, value))
                .map(ValueGrammar.Written::source)
                .orElse(source == null ? "" : source.strip());
    }

    private static String canonical(ValueGrammar grammar, Type leaf, ValueGrammar.Part part) {
        return grammar.valueOf(leaf, part.written())
                .flatMap(value -> grammar.spell(leaf, value))
                .map(ValueGrammar.Written::source)
                .orElse(part.source());
    }

    // --- small helpers ------------------------------------------------------------------------------------

    private static List<String> importsOf(List<List<String>> lists) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> each : lists) if (each != null) out.addAll(each);
        return List.copyOf(out);
    }

    private static List<String> lines(TextArea area) {
        return area.getText() == null ? List.of()
                : area.getText().lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint-text");
        return label;
    }
}
