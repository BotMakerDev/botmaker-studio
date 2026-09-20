package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.plugin.ValueWire;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the value-entry widget for one {@link ParameterRow}, seeded from the Java its value is written as,
 * and hands back a reader turning the widget's live state back into Java.
 *
 * <p><b>Source in, source out (2026-09-20).</b> A row's value is the initialiser its field takes, because a
 * composite has no other canonical form — so a cell reads and writes that spelling and nothing else. A
 * <em>leaf</em> still has a second text, the one its own control shows, and {@link ValueWire#literal} and
 * {@link ValueWire#wire} are the join at that one point. A composite is never encoded: it is taken apart one
 * level at a time into parts that are themselves source, edited, and composed back through its container's
 * own factory.
 *
 * <p><b>Reading is total and never validates.</b> A half-typed duration, a number past its bound, a template
 * that has since been deleted: every one of them is handed on as typed, and what it is finally stored as is
 * the owning plugin's answer to a {@code ParameterEdit} — clamped, canonicalised, pruned to the choices still
 * on offer. Nothing here can refuse a value, so nothing here can leave a window unable to close because of a
 * limit somebody tightened afterwards. The one refusal is a duplicate map key, and it happens <em>at the
 * cell</em> rather than at the read: two entries with one key is not a map at all, and {@code Map.ofEntries}
 * throws at runtime rather than keeping the last.
 *
 * <p><b>The form decides the widget, and it decides it unconditionally.</b> A leaf is its type's own editor,
 * a list is rows, a map is two columns. Anything else — a container a plugin contributed, a class the bot
 * declares, a nesting deeper than the cell draws, a leaf nothing registered — is shown <b>as the author
 * wrote it</b> and not edited. Read-only is a first-class outcome here
 * ({@code docs/refactor/32-generic-values.md}), not a failure path: the window never hides a parameter the
 * bot reads.
 *
 * <p>Shared by the Parameters dialog and the Runner window, so a type is entered the same way wherever it is
 * met.
 */
public final class ParamValueWidgets {

    /** How wide a value column gets, so a row of them reads as a column rather than as ragged text. */
    private static final double VALUE_WIDTH = 260;

    private ParamValueWidgets() {}

    /**
     * A variable's handle plus a reader turning its widget's UI state back into the Java the field takes.
     *
     * <p>The handle is the <b>pair</b> {@code (group, name)}: a name identifies a variable only inside its
     * own plugin's section, so a reader holding just the name could write one plugin's value into another
     * plugin's variable that happens to share it.
     *
     * <p>A blank reading means <em>this widget has no source spelling for what is in it</em> — an empty
     * radio group, a leaf whose codec declined — and a caller writes nothing rather than guessing.
     */
    public record ValueEditor(String group, String name, Supplier<String> read) {

        static ValueEditor of(String group, ParameterRow row, Supplier<String> read) {
            return new ValueEditor(group, row.name(), read);
        }

        /** True when this reader was built from the row called {@code rowName} in {@code rowGroup}. */
        public boolean describes(String rowGroup, String rowName) {
            return name.equals(rowName) && group.equals(rowGroup == null ? "" : rowGroup);
        }
    }

    /**
     * The widget for one {@link ParameterRow} of {@code group}, seeded from its current value.
     *
     * @param group  the section the row is filed under — half of the handle a reader is keyed by
     * @param config the project, needed by the one type whose picker reads from disk ({@code IMAGE_TEMPLATE})
     */
    public static Node build(String group, ParameterRow row, ProjectConfig config, List<ValueEditor> sink) {
        return build(group, row, config, BotRecords.none(), sink);
    }

    /**
     * The same, told what the bot declares itself — which is what lets a field typed with one of the bot's
     * own records be edited component by component rather than shown as written.
     */
    public static Node build(String group, ParameterRow row, ProjectConfig config, BotRecords records,
                             List<ValueEditor> sink) {
        String owner = group == null ? "" : group;
        ValueEditors.Context ctx = new ValueEditors.Context(config, row.bounds());
        Node widget = cell(owner, row, records == null ? BotRecords.none() : records, ctx, sink);
        widget.setId("param-value-" + row.name());
        return widget;
    }

    /** The same widget, pinned to one width — what a list of rows wants, and a form does not. */
    public static Node buildFixedWidth(String group, ParameterRow row, ProjectConfig config,
                                       List<ValueEditor> sink) {
        Node widget = build(group, row, config, sink);
        if (widget instanceof javafx.scene.layout.Region region) region.setPrefWidth(VALUE_WIDTH);
        return widget;
    }

    /**
     * One case of {@link ValueForm} per widget, and a read-only fallback for the rest.
     *
     * <p>A contributed container draws read-only on purpose. The host knows how to write and read one — that
     * is what {@link ValueContainer} is for — but it has no idea what a row of one should look like, and a
     * cell that guessed would be this window deciding something the plugin declined to. The value is shown
     * as written, which is exactly what a plugin gets for free by contributing a container at all.
     */
    private static Node cell(String group, ParameterRow row, BotRecords records, ValueEditors.Context ctx,
                             List<ValueEditor> sink) {
        ValueForm form = row.form();
        List<String> options = declaredOptions(row, form);

        if (form instanceof ValueForm.Declared declared) {
            return records.whyNotEditable(declared) == null
                    ? recordRows(group, row, declared, records, ctx, sink)
                    : unreadable(row, records.whyNotEditable(declared));
        }

        // A leaf nothing registered is not sent down the read-only path: its own editor already draws it as a
        // disabled field holding the text as written, and every reader here answers blank for it, so a
        // caller writes nothing back over it. That is the ValueType.unknown rule, unchanged.
        if (form instanceof ValueForm.Leaf leaf) {
            return options.isEmpty()
                    ? single(group, row, leaf.type(), ctx, sink)
                    : radioRow(group, row, leaf.type(), options, ctx, sink);
        }
        if (form instanceof ValueForm.Of of && ValueContainer.LIST.id().equals(of.container().id())
                && of.last() instanceof ValueForm.Leaf leaf) {
            return options.isEmpty()
                    ? listRows(group, row, form, leaf.type(), ctx, sink)
                    : checkList(group, row, form, leaf.type(), options, ctx, sink);
        }
        if (form instanceof ValueForm.Of of && ValueContainer.MAP.id().equals(of.container().id())
                && of.arguments().get(0) instanceof ValueForm.Leaf key
                && of.arguments().get(1) instanceof ValueForm.Leaf value) {
            return mapGrid(group, row, form, key.type(), value.type(), ctx, sink);
        }
        return unreadable(row, "no editor here writes " + form.sourceName());
    }

    /**
     * The choices in force for this row: the type's own when it has any, else the ones the author declared.
     *
     * <p><b>Declared and none are different states, and only the first is a set.</b> A row with no options
     * written down is a free value of its type and gets that type's own editor — which for a closed-set type
     * is already a pad, a search or a dropdown. Options live on the row and never on the form: what a value
     * may be is the declaration's business, and what type it is the field's.
     */
    private static List<String> declaredOptions(ParameterRow row, ValueForm form) {
        if (row.options().isEmpty()) return List.of();
        return ValueWire.effectiveOptions(ValueWire.leafOf(form), row.options());
    }

    // --- the four editable cells -------------------------------------------------------------------------

    private static Node single(String group, ParameterRow row, ValueType leaf,
                               ValueEditors.Context ctx, List<ValueEditor> sink) {
        // A type nothing registered has no stored text to seed from, so the field shows the initialiser as
        // the author wrote it — which is what its own editor disables itself to display.
        String seed = leaf.known() ? ValueWire.wire(leaf, row.value()) : row.value();
        ValueEditors.Editor editor = ValueEditors.editorFor(leaf, seed, ctx);
        Node widget = editor.node();
        ValueEditors.stretch(widget);
        sink.add(ValueEditor.of(group, row, () -> ValueWire.literal(leaf, editor.read().get())));
        return widget;
    }

    /**
     * One radio button per declared choice, at most one of them on.
     *
     * <p>Nothing selected is a legal state and the honest one: a stored value the editor has since removed
     * from the list shows as no selection rather than as the first choice, which would be this widget
     * choosing a setting on the user's behalf. It reads back blank, and a blank reading is written nowhere.
     */
    private static Node radioRow(String group, ParameterRow row, ValueType leaf, List<String> options,
                                 ValueEditors.Context ctx, List<ValueEditor> sink) {
        ToggleGroup toggles = new ToggleGroup();
        VBox column = new VBox(2);
        String current = ValueWire.wire(leaf, row.value());
        for (String option : options) {
            RadioButton button = new RadioButton(option);
            button.setToggleGroup(toggles);
            button.setUserData(option);
            button.setGraphic(ValueEditors.optionGraphic(leaf, option, ctx));
            button.setSelected(option.equals(current));
            column.getChildren().add(button);
        }
        if (options.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(group, row, () -> {
            Toggle chosen = toggles.getSelectedToggle();
            return chosen == null ? "" : ValueWire.literal(leaf, (String) chosen.getUserData());
        }));
        return column;
    }

    /** Declared choices, ticked — the list shape of {@link #radioRow}. */
    private static Node checkList(String group, ParameterRow row, ValueForm form, ValueType leaf,
                                  List<String> options, ValueEditors.Context ctx, List<ValueEditor> sink) {
        List<String> held = leafWires(form, row.value(), leaf);
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(2);
        for (String option : options) {
            CheckBox box = new CheckBox(option);
            box.setUserData(option);
            box.setGraphic(ValueEditors.optionGraphic(leaf, option, ctx));
            box.setSelected(held.contains(option));
            boxes.add(box);
            column.getChildren().add(box);
        }
        if (boxes.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(group, row, () -> ValueWire.compose(form, boxes.stream()
                .filter(CheckBox::isSelected)
                .map(box -> ValueWire.literal(leaf, (String) box.getUserData()))
                .toList())));
        return column;
    }

    /**
     * A list the user fills in themselves, out of no set at all.
     *
     * <p>Text is one item per line — a newline is not a character anybody types into a value by accident,
     * where a comma is, and twenty strings are faster typed than clicked. Every other type gets a growable
     * column of that type's own editor instead: a list of durations typed as text is four numbers per line to
     * decode, and a list of templates typed as text is names remembered rather than pictures chosen.
     */
    private static Node listRows(String group, ParameterRow row, ValueForm form, ValueType leaf,
                                 ValueEditors.Context ctx, List<ValueEditor> sink) {
        List<String> items = leafWires(form, row.value(), leaf);
        if (ValueCatalog.TEXT_ID.equals(leaf.id())) {
            TextArea area = new TextArea(String.join("\n", items));
            area.setPrefRowCount(Math.max(3, Math.min(8, items.size() + 1)));
            area.setPromptText("One per line");
            sink.add(ValueEditor.of(group, row, () -> ValueWire.compose(form, lines(area).stream()
                    .map(line -> ValueWire.literal(leaf, line))
                    .toList())));
            return area;
        }

        List<ValueEditors.Editor> editors = new ArrayList<>();
        VBox column = new VBox(4);
        Button add = new Button("Add");
        Runnable[] rebuild = new Runnable[1];

        // The rows are rebuilt from the editors' own current text rather than from the row: this widget
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

        for (String item : items) editors.add(ValueEditors.editorFor(leaf, item, ctx));
        add.setOnAction(e -> {
            editors.add(ValueEditors.editorFor(leaf, null, ctx));
            rebuild[0].run();
        });
        rebuild[0].run();

        sink.add(ValueEditor.of(group, row, () -> ValueWire.compose(form, editors.stream()
                .map(editor -> ValueWire.literal(leaf, editor.read().get()))
                .filter(source -> !source.isBlank())
                .toList())));
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
    private static Node mapGrid(String group, ParameterRow row, ValueForm form, ValueType keyType,
                                ValueType valueType, ValueEditors.Context ctx, List<ValueEditor> sink) {
        ValueForm entryForm = form instanceof ValueForm.Of of
                ? of.container().partForms(of.arguments(), 1).getFirst()
                : ValueForm.of(ValueType.unknown(""));

        List<Entry> entries = new ArrayList<>();
        for (ValueCatalog.Part part : ValueWire.partsOrNone(form, row.value())) {
            List<ValueCatalog.Part> pair = ValueWire.partsOrNone(part.form(), part.initializer());
            if (pair.size() != 2) continue;
            entries.add(new Entry(ValueWire.wire(keyType, pair.get(0).initializer()),
                    ValueWire.wire(valueType, pair.get(1).initializer())));
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
                String key = ValueWire.literal(keyType, cell.key().read().get());
                String value = ValueWire.literal(valueType, cell.value().read().get());
                // The duplicate is refused here as well as at the cell, because a key may be typed into a row
                // that is never focused out of. The first one written wins, which is the one on screen above.
                if (key.isBlank() || value.isBlank() || seen.contains(key)) continue;
                seen.add(key);
                written.add(ValueWire.compose(entryForm, List.of(key, value)));
            }
            return ValueWire.compose(form, written);
        }));
        return column;
    }

    /**
     * A record the bot declares, as one labelled row per component.
     *
     * <p><b>The components are fixed, so there is no Add and no ✕.</b> That is the whole difference between
     * this cell and the two above it: a list and a map are as long as the user makes them, and a record is
     * exactly as long as its own declaration — changing that is editing the record, which is a thing to do
     * in the file and not in a parameters window.
     *
     * <p>A component whose own form is not a leaf keeps the source it already had, shown beside its name and
     * written back untouched. A record of a record is legal, reads, and is edited one file's worth at a
     * time; drawing an editor for it here would be a cell inside a cell inside a table row.
     */
    private static Node recordRows(String group, ParameterRow row, ValueForm.Declared declared,
                                   BotRecords records, ValueEditors.Context ctx, List<ValueEditor> sink) {
        List<BotRecords.Component> components = records.componentsOf(declared);
        List<ValueCatalog.Part> held = records.partsOf(declared, row.value()).orElse(List.of());

        VBox column = new VBox(4);
        List<Supplier<String>> readers = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            BotRecords.Component component = components.get(i);
            String written = i < held.size() ? held.get(i).initializer() : "";
            Label name = new Label(component.name());
            name.getStyleClass().add("dialog-hint-text");
            name.setMinWidth(72);

            Node control;
            if (component.form() instanceof ValueForm.Leaf leaf && leaf.type().known()) {
                ValueEditors.Editor editor = ValueEditors.editorFor(
                        leaf.type(), ValueWire.wire(leaf.type(), written), ctx);
                control = editor.node();
                readers.add(() -> ValueWire.literal(leaf.type(), editor.read().get()));
            } else {
                Label shown = new Label(written.isBlank() ? "—" : written);
                shown.getStyleClass().add("dialog-hint-text");
                shown.setTooltip(new Tooltip("Kept as written: " + component.form().sourceName()
                                             + " is edited where the record is."));
                control = shown;
                readers.add(() -> written);
            }

            HBox line = new HBox(6, name, control);
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(control, Priority.ALWAYS);
            column.getChildren().add(line);
        }
        if (components.isEmpty()) column.getChildren().add(hint("This record has no components."));

        sink.add(ValueEditor.of(group, row, () -> records.initializerOfParts(declared,
                readers.stream().map(Supplier::get).toList()).orElse("")));
        return column;
    }

    /** One entry's two halves as the text their own controls show. */
    private record Entry(String key, String value) {}

    /** One map row, with the key field watching for a key another row already has. */
    private static Cell watched(List<Cell> cells, ValueType keyType, ValueType valueType, Entry entry,
                                ValueEditors.Context ctx) {
        Cell cell = new Cell(ValueEditors.editorFor(keyType, entry.key(), ctx),
                ValueEditors.editorFor(valueType, entry.value(), ctx));
        cell.key().node().focusedProperty().addListener((o, was, is) -> {
            if (!is) markDuplicates(cells, keyType);
        });
        return cell;
    }

    /**
     * Marks every map row whose key a row above it already has.
     *
     * <p>Compared as the <b>source</b> each key is written as, not as the text typed into the field: two
     * spellings of one duration are one key to {@code Map.ofEntries}, and a check on the typed text would
     * pass them both and hand the bot a map that throws when it is built.
     */
    private static void markDuplicates(List<Cell> cells, ValueType keyType) {
        List<String> seen = new ArrayList<>();
        for (Cell cell : cells) {
            String key = ValueWire.literal(keyType, cell.key().read().get());
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

    // --- small helpers ------------------------------------------------------------------------------------

    /**
     * A container's parts as the text a leaf editor shows, one per part — empty when nothing here can read
     * the source, which is the same answer as a container the user has not filled in.
     */
    private static List<String> leafWires(ValueForm form, String source, ValueType leaf) {
        return ValueWire.partsOrNone(form, source).stream()
                .map(part -> ValueWire.wire(leaf, part.initializer()))
                .filter(wire -> !wire.isBlank())
                .toList();
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
