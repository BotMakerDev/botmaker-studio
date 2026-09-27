package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.slot.Bounds;
import com.botmaker.studio.project.params.ParameterRow;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.ValueWire;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.ChoiceMode;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds the value-entry widget for one {@link ParameterRow}, seeded from the Java its value is written as,
 * and hands back a reader turning the widget's live state back into a {@link JavaValue} tree.
 *
 * <p><b>Source in, a tree out (2026-09-20, trees since 2026-09-24).</b> A row's value is the initialiser its
 * field takes, because a composite has no other canonical form. A leaf is a plugin's editor over that leaf's
 * own Java; a composite is taken apart one level at a time into parts, edited, and composed back through its
 * container's own factory as a tree — a part nothing edited is its own expression, kept, never its text.
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
 * a list is rows, a map is two columns, a record the bot declares is one row per component — and each part of
 * those is drawn the same way, as deep as the type goes (2026-09-26; one level until then). Anything else — a
 * container nobody registered, a class the bot declares that is not a record — is shown <b>as the author
 * wrote it</b> and not edited. Read-only is a first-class outcome here ({@code docs/refactor/32-generic-values.md}), not a
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
     * A variable's handle plus a reader turning its widget's UI state back into the value the field takes, as
     * a tree with the imports it names.
     *
     * <p>An empty reading means <em>this widget has no Java for what is in it</em> — an empty radio group, a
     * leaf no editor could draw — and a caller writes nothing rather than guessing.
     */
    public record ValueEditor(String group, String name, Supplier<Optional<JavaValue>> read) {

        static ValueEditor of(String group, ParameterRow row, Supplier<Optional<JavaValue>> read) {
            return new ValueEditor(group, row.name(), read);
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
        ValueEditors.Context ctx = ValueEditors.Context.of(config).withBounds(new Bounds(row.min(), row.max()));
        Node widget = cell(owner, row, form == null ? ValueTypes.NONE : form,
                records == null ? BotRecords.none() : records, ctx, sink);
        widget.setId("param-value-" + row.name());
        return widget;
    }

    /**
     * The editor for one value of {@code form} outside the Parameters window — the canvas's container
     * popover (picker 6e2) — seeded from {@code written}, with the same rows, grid and cells a row gets.
     */
    public static ValueEditors.Editor valueEditor(Type form, Optional<SourceNode> written, ProjectConfig config,
                                                  BotRecords records) {
        return editor(PluginHost.grammar(), form, written, records == null ? BotRecords.none() : records,
                ValueEditors.Context.of(config));
    }

    /**
     * The editor for one declared choice of {@code base}, seeded from {@code source} (a fresh value when
     * null): a leaf's own editor, told each value it writes through {@code onChange}, or a container's own
     * rows, which tell nobody and are read when their row is left (2026-09-27).
     */
    static ValueEditors.Editor choiceEditor(Type base, String source, ValueEditors.Context ctx, BotRecords records,
                                            java.util.function.Consumer<JavaValue> onChange) {
        if (ValueTypes.isLeaf(base)) return ValueEditors.framed(ValueEditors.editorFor(base, source, ctx, onChange));
        Optional<SourceNode> written = source == null ? Optional.empty() : SourceNode.parse(source);
        return editor(PluginHost.grammar(), base, written, records == null ? BotRecords.none() : records, ctx);
    }

    /** The same widget, pinned to one width — what a list of rows wants, and a form does not. */
    public static Node buildFixedWidth(String group, ParameterRow row, Type form, ProjectConfig config,
                                       List<ValueEditor> sink) {
        Node widget = build(group, row, form, config, sink);
        if (widget instanceof javafx.scene.layout.Region region) region.setPrefWidth(VALUE_WIDTH);
        return widget;
    }

    /**
     * A declared set of choices is radio buttons or ticks; anything else is {@link #editor}, one level at a
     * time as deep as the type goes, or read-only as a whole when some part of it has no editor at all.
     */
    private static Node cell(String group, ParameterRow row, Type form, BotRecords records,
                             ValueEditors.Context ctx, List<ValueEditor> sink) {
        ValueGrammar grammar = PluginHost.grammar();
        List<String> options = row.options();

        // The choices' shape says which (ChoiceMode): one of them — a leaf, or one of several whole lists — or
        // any number of them, ticked. An enum with none written down is picked from every constant, which is
        // what its own editor already offers, so it falls through to that editor (feedback 3).
        switch (ChoiceMode.of(form, options, grammar)) {
            case ONE -> {
                if (!options.isEmpty()) return radioRow(grammar, group, row, form, options, ctx, sink);
            }
            case MANY -> {
                Type leaf = ValueTypes.arguments(form).getLast();
                if (ChoiceMode.toggled(leaf) && !options.isEmpty()) {
                    List<Object> declared = options.stream()
                            .flatMap(option -> grammar.valueOf(leaf, option).stream()).toList();
                    ValueEditors.Editor toggles = enumToggles(grammar, form, (Class<?>) leaf, declared,
                            SourceNode.parse(row.value()));
                    sink.add(ValueEditor.of(group, row, toggles.read()));
                    return toggles.node();
                }
                if (!options.isEmpty()) {
                    return checkList(grammar, group, row, form, leaf, options, ctx, sink);
                }
            }
            case NONE -> { }
        }
        String why = whyNotEditable(form, records);
        if (why != null) return unreadable(row, why);
        ValueEditors.Editor editor = editor(grammar, form, SourceNode.parse(row.value()), records, ctx);
        ValueEditors.stretch(editor.node());
        sink.add(ValueEditor.of(group, row, editor.read()));
        return editor.node();
    }

    /**
     * Why no cell here can write {@code form}, or null when one can — at any depth: a list of maps of records
     * is editable when every part of it is.
     *
     * <p>A leaf is always drawn: a leaf nothing declares is its own editor's disabled field, holding the Java
     * as written and read back untouched.
     */
    static String whyNotEditable(Type form, BotRecords records) {
        if (ValueTypes.isLeaf(form)) return null;
        if (form instanceof ValueTypes.BotClass declared) return records.whyNotEditable(declared);
        Optional<ValueContainer<?>> container = ValueTypes.container(form);
        if (container.isEmpty()) return "no editor here writes " + ValueTypes.sourceName(form);
        for (Type argument : ValueTypes.arguments(form)) {
            String why = whyNotEditable(argument, records);
            if (why != null) return why;
        }
        return null;
    }

    // --- the editable cells --------------------------------------------------------------------------------

    /**
     * The editor for one value of {@code form}, seeded from {@code written} — a leaf's own editor, a list as
     * rows, a map as two columns, a record as one row per component, each part drawn by this same method.
     *
     * <p><b>A part nothing edited reads back as it was written</b>, so an editor that answers blank for a
     * value it holds (a leaf no plugin draws) never drops the part out of the composite around it. A part
     * {@link #whyNotEditable} refuses is shown as written and kept.
     */
    static ValueEditors.Editor editor(ValueGrammar grammar, Type form, Optional<SourceNode> written,
                                      BotRecords records, ValueEditors.Context ctx) {
        Optional<JavaValue> kept = written.map(JavaValue::kept);
        if (ValueTypes.isLeaf(form)) {
            ValueEditors.Editor leaf = ValueEditors.framed(
                    ValueEditors.editorFor(form, written.map(SourceNode::source).orElse(null), ctx));
            return new ValueEditors.Editor(leaf.node(), () -> leaf.read().get().or(() -> kept));
        }
        if (whyNotEditable(form, records) != null) return keptAsWritten(form, written);
        if (form instanceof ValueTypes.BotClass declared) {
            return recordRows(grammar, declared, written, records, ctx);
        }
        ValueContainer<?> container = ValueTypes.container(form).orElseThrow();
        List<Type> arguments = ValueTypes.arguments(form);
        if ((container == ValueContainer.LIST || container == ValueContainer.SET)
                && ChoiceMode.toggled(arguments.getLast()) && grammar.known(arguments.getLast())) {
            // Only an enum the grammar reads: one nobody declares could be neither read nor written, and the
            // strip would hand back an empty list over the author's. Only a small one: a hundred keys as
            // toggles is a wall, and rows of the key's own picker are how a few of them are chosen.
            Class<?> cls = (Class<?>) arguments.getLast();
            return enumToggles(grammar, form, cls, List.of(cls.getEnumConstants()), written);
        }
        if (container == ValueContainer.LIST) {
            return arguments.getLast() == String.class
                    ? textLines(grammar, form, written)
                    : listRows(grammar, form, arguments.getLast(), written, records, ctx, false);
        }
        if (container == ValueContainer.SET) {
            return listRows(grammar, form, arguments.getLast(), written, records, ctx, true);
        }
        if (container == ValueContainer.DEQUE) {
            return dequeRows(grammar, form, written, records, ctx);
        }
        if (container == ValueContainer.MAP) {
            return mapGrid(grammar, form, arguments.get(0), arguments.get(1), written, records, ctx);
        }
        return keptAsWritten(form, written);
    }

    /** A part no cell here writes: its Java as written, handed back untouched. */
    private static ValueEditors.Editor keptAsWritten(Type form, Optional<SourceNode> written) {
        Label shown = new Label(written.map(SourceNode::source).orElse("—"));
        shown.getStyleClass().add("dialog-hint-text");
        shown.setTooltip(new Tooltip("Kept as written: no editor here writes " + ValueTypes.sourceName(form) + "."));
        Optional<JavaValue> kept = written.map(JavaValue::kept);
        return new ValueEditors.Editor(shown, () -> kept);
    }

    /** A column of a composite's rows, marked so a composite inside another one is indented beneath it. */
    private static VBox composite() {
        VBox column = new VBox(4);
        column.getStyleClass().add("param-composite");
        return column;
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
        Optional<JavaValue> current = canonical(grammar, leaf, SourceNode.parse(row.value()));
        for (String option : options) {
            Optional<JavaValue> written = optionSource(grammar, leaf, option);
            RadioButton button = new RadioButton();
            button.setToggleGroup(toggles);
            button.setUserData(written.orElse(null));
            button.setDisable(written.isEmpty());
            showOption(button, leaf, option, written, ctx);
            written.ifPresent(w -> button.setSelected(w.sameJava(current.orElse(null))));
            column.getChildren().add(button);
        }
        if (options.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(group, row, () -> chosen(toggles)));
        return column;
    }

    private static Optional<JavaValue> chosen(ToggleGroup toggles) {
        Toggle selected = toggles.getSelectedToggle();
        return selected != null && selected.getUserData() instanceof JavaValue written
                ? Optional.of(written) : Optional.empty();
    }

    /** Declared choices, ticked — the list shape of {@link #radioRow}. */
    private static Node checkList(ValueGrammar grammar, String group, ParameterRow row, Type form,
                                  Type leaf, List<String> options, ValueEditors.Context ctx,
                                  List<ValueEditor> sink) {
        List<JavaValue> held = ValueWire.partsOrNone(form, row.value()).stream()
                .flatMap(part -> canonical(grammar, leaf, Optional.of(part.written())).stream())
                .toList();
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(2);
        for (String option : options) {
            Optional<JavaValue> written = optionSource(grammar, leaf, option);
            CheckBox box = new CheckBox();
            box.setUserData(written.orElse(null));
            box.setDisable(written.isEmpty());
            showOption(box, leaf, option, written, ctx);
            written.ifPresent(w -> box.setSelected(held.stream().anyMatch(w::sameJava)));
            boxes.add(box);
            column.getChildren().add(box);
        }
        if (boxes.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(group, row, () -> ValueWire.compose(form, boxes.stream()
                .filter(CheckBox::isSelected)
                .map(box -> box.getUserData() instanceof JavaValue w ? w : null)
                .filter(w -> w != null)
                .toList())));
        return column;
    }

    /**
     * A list the user fills in themselves, out of no set at all.
     *
     * <p>Text is one item per line — a newline is not a character anybody types into a value by accident,
     * where a comma is, and twenty strings are faster typed than clicked. Every other type gets a growable
     * column of that type's own editor instead.
     */
    private static ValueEditors.Editor textLines(ValueGrammar grammar, Type form, Optional<SourceNode> written) {
        List<String> items = parts(form, written).stream()
                .map(part -> grammar.valueOf(String.class, part.written()).orElse(null))
                .filter(value -> value instanceof String)
                .map(value -> (String) value)
                .toList();
        TextArea area = new TextArea(String.join("\n", items));
        area.setPrefRowCount(Math.max(3, Math.min(8, items.size() + 1)));
        area.setPromptText("One per line");
        return new ValueEditors.Editor(area, () -> ValueWire.compose(form, lines(area).stream()
                .flatMap(line -> grammar.spell(String.class, line).stream())
                .toList()));
    }

    /** {@code written} taken apart one level, or no parts for a fresh value or one nothing can read. */
    private static List<ValueGrammar.Part> parts(Type form, Optional<SourceNode> written) {
        return written.map(node -> ValueWire.partsOrNone(form, node)).orElse(List.of());
    }

    /**
     * A list of anything but text, or a set: a growable column of the element's own editor, each row with
     * ↑/↓ to reorder, ⧉ to duplicate and ✕ to remove.
     *
     * <p>A set ({@code distinct}) refuses a repeated element when it is read back, keeping the first:
     * {@code Set.of} throws on one, so writing it would be a bot that stops at start-up. Its ⧉ is left out for
     * the same reason — the copy could only ever be refused.
     */
    private static ValueEditors.Editor listRows(ValueGrammar grammar, Type form, Type element,
                                                Optional<SourceNode> written, BotRecords records,
                                                ValueEditors.Context ctx, boolean distinct) {
        List<ValueEditors.Editor> editors = new ArrayList<>();
        for (ValueGrammar.Part part : parts(form, written)) {
            editors.add(editor(grammar, part.form(), Optional.of(part.written()), records, ctx));
        }
        Node column = growable(editors, distinct ? "Nothing in this set yet." : "Nothing in this list yet.",
                () -> editor(grammar, element, Optional.empty(), records, ctx),
                distinct ? null : original -> editor(grammar, element,
                        original.read().get().map(JavaValue::written), records, ctx),
                editor -> List.of(editor.node()));

        return new ValueEditors.Editor(column, () -> {
            List<JavaValue> values = new ArrayList<>();
            for (ValueEditors.Editor editor : editors) {
                Optional<JavaValue> value = editor.read().get();
                if (value.isEmpty()) continue;
                if (distinct && values.stream().anyMatch(value.get()::sameJava)) continue;
                values.add(value.get());
            }
            return ValueWire.compose(form, values);
        });
    }

    /**
     * A list or a set of an enum's constants, as one toggle per constant in the enum's own order
     * (2026-09-27) — seven days, five mouse buttons — rather than rows each holding a picker of the same few
     * values. Read back in constant order, each at most once, which is what a set is and what a list of days
     * means; a hand-written list that repeats a constant or orders them otherwise is rewritten that way only
     * when a toggle is touched. A stack or a queue keeps its rows: there the order is the value.
     *
     * <p>{@code offered} are the constants drawn: every one, or the choices declared for the field.
     */
    private static ValueEditors.Editor enumToggles(ValueGrammar grammar, Type form, Class<?> constants,
                                                   List<?> offered, Optional<SourceNode> written) {
        List<Object> held = parts(form, written).stream()
                .flatMap(part -> grammar.valueOf(constants, part.written()).stream())
                .toList();
        javafx.scene.layout.FlowPane strip = new javafx.scene.layout.FlowPane(2, 2);
        List<ToggleButton> toggles = new ArrayList<>();
        for (Object constant : offered) {
            String name = ((Enum<?>) constant).name();
            ToggleButton toggle = new ToggleButton(EnumLabels.label(name));
            toggle.setTooltip(new Tooltip(name));
            toggle.setUserData(constant);
            toggle.setSelected(held.contains(constant));
            toggles.add(toggle);
        }
        strip.getChildren().addAll(toggles);
        Optional<JavaValue> kept = written.map(JavaValue::kept);
        boolean[] touched = {false};
        toggles.forEach(toggle -> toggle.setOnAction(e -> touched[0] = true));
        return new ValueEditors.Editor(strip, () -> {
            // Untouched, the list is handed back as it was written — opening a window rewrites nothing.
            if (!touched[0] && kept.isPresent()) return kept;
            return ValueWire.compose(form, toggles.stream()
                    .filter(ToggleButton::isSelected)
                    .flatMap(toggle -> grammar.spell(constants, toggle.getUserData()).stream())
                    .toList());
        });
    }

    /**
     * A stack or a queue ({@code Deque}): the list it wraps, drawn as {@link #listRows}, and composed back
     * inside {@code new ArrayDeque<>(…)}. The top of a stack and the head of a queue are the first row.
     */
    private static ValueEditors.Editor dequeRows(ValueGrammar grammar, Type form, Optional<SourceNode> written,
                                                 BotRecords records, ValueEditors.Context ctx) {
        Type listForm = ValueTypes.container(form)
                .map(container -> container.partTypes(ValueTypes.arguments(form), 1).getFirst())
                .orElse(ValueTypes.NONE);
        Optional<SourceNode> inner = parts(form, written).stream().findFirst().map(ValueGrammar.Part::written);
        List<Type> arguments = ValueTypes.arguments(form);
        Type element = arguments.isEmpty() ? ValueTypes.NONE : arguments.getFirst();
        ValueEditors.Editor list = element == String.class
                ? textLines(grammar, listForm, inner)
                : listRows(grammar, listForm, element, inner, records, ctx, false);
        Label order = hint("First row: the top of a stack, the head of a queue.");
        return new ValueEditors.Editor(new VBox(4, order, list.node()),
                () -> list.read().get().flatMap(value -> ValueWire.compose(form, List.of(value))));
    }

    /**
     * The shared row machinery of a list, a set and a map: each row is {@code cells(item)} followed by ↑, ↓,
     * ⧉ (when {@code copy} is not {@code null}) and ✕, and an Add at the foot.
     *
     * <p>The rows are rebuilt from {@code items} itself rather than from the row the window was opened on:
     * this widget outlives several adds, moves and removes before anything is flushed back, so the row it was
     * built from is stale from the first click. {@code copy} seeds the duplicate from what the original reads
     * back now, so an edit not yet written is copied too.
     */
    private static <T> Node growable(List<T> items, String empty, java.util.function.Supplier<T> fresh,
                                     java.util.function.UnaryOperator<T> copy,
                                     java.util.function.Function<T, List<Node>> cells) {
        boolean duplicable = copy != null;
        VBox column = composite();
        Button add = new Button("Add");
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            column.getChildren().clear();
            for (int i = 0; i < items.size(); i++) {
                T item = items.get(i);
                int at = i;
                HBox line = new HBox(6);
                line.getChildren().addAll(cells.apply(item));
                Button up = rowButton("↑", "Move up", () -> {
                    Collections.swap(items, at, at - 1);
                    rebuild[0].run();
                });
                up.setDisable(at == 0);
                Button down = rowButton("↓", "Move down", () -> {
                    Collections.swap(items, at, at + 1);
                    rebuild[0].run();
                });
                down.setDisable(at == items.size() - 1);
                line.getChildren().addAll(up, down);
                if (duplicable) {
                    line.getChildren().add(rowButton("⧉", "Duplicate", () -> {
                        items.add(at + 1, copy.apply(item));
                        rebuild[0].run();
                    }));
                }
                line.getChildren().add(rowButton("✕", "Remove", () -> {
                    items.remove(at);
                    rebuild[0].run();
                }));
                line.setAlignment(Pos.CENTER_LEFT);
                for (Node cell : cells.apply(item)) HBox.setHgrow(cell, Priority.ALWAYS);
                column.getChildren().add(line);
            }
            if (items.isEmpty()) column.getChildren().add(hint(empty));
            column.getChildren().add(add);
        };
        add.setOnAction(e -> {
            items.add(fresh.get());
            rebuild[0].run();
        });
        rebuild[0].run();
        return column;
    }

    private static Button rowButton(String glyph, String tip, Runnable action) {
        Button button = new Button(glyph);
        button.getStyleClass().add("row-icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> action.run());
        return button;
    }

    /**
     * A map as two columns of its own editors, one row per entry.
     *
     * <p><b>A duplicate key is refused at the cell it was typed into</b>, marked there the moment the field
     * is left, rather than reported when the window closes. {@code Map.ofEntries} throws on a repeated key,
     * so a map that wrote one would be a bot that stops running; the marked row is the one not written, and
     * it stays on screen holding what was typed, so nothing the user entered disappears silently.
     */
    private static ValueEditors.Editor mapGrid(ValueGrammar grammar, Type form, Type keyType, Type valueType,
                                               Optional<SourceNode> written, BotRecords records,
                                               ValueEditors.Context ctx) {
        Type entryForm = ValueTypes.container(form)
                .map(container -> container.partTypes(ValueTypes.arguments(form), 1).getFirst())
                .orElse(ValueTypes.NONE);

        List<Entry> entries = new ArrayList<>();
        for (ValueGrammar.Part part : parts(form, written)) {
            List<ValueGrammar.Part> pair = ValueWire.partsOrNone(part.form(), part.written());
            if (pair.size() != 2) continue;
            entries.add(new Entry(Optional.of(pair.get(0).written()), Optional.of(pair.get(1).written())));
        }
        Factory cellOf = entry -> new Cell(editor(grammar, keyType, entry.key(), records, ctx),
                editor(grammar, valueType, entry.value(), records, ctx));

        List<Cell> cells = new ArrayList<>();
        // The watcher is wired once per cell, where the cell is made: the rows are rebuilt on every add, move
        // and remove, so wiring it there would stack one listener per rebuild on the same field.
        for (Entry entry : entries) cells.add(watched(cells, cellOf.cell(entry)));
        // ⧉ copies the entry as it reads back now; the copy's key is then marked as a repeat until changed,
        // which is the point of duplicating an entry — keep the value, give it a new key.
        Node column = growable(cells, "Nothing in this map yet.",
                () -> watched(cells, cellOf.cell(new Entry(Optional.empty(), Optional.empty()))),
                original -> watched(cells, cellOf.cell(new Entry(
                        original.key().read().get().map(JavaValue::written),
                        original.value().read().get().map(JavaValue::written)))),
                cell -> List.of(cell.key().node(), new Label("→"), cell.value().node()));

        return new ValueEditors.Editor(column, () -> {
            List<JavaValue> pairs = new ArrayList<>();
            List<JavaValue> seen = new ArrayList<>();
            for (Cell cell : cells) {
                Optional<JavaValue> key = cell.key().read().get();
                Optional<JavaValue> value = cell.value().read().get();
                // The duplicate is refused here as well as at the cell, because a key may be typed into a row
                // that is never focused out of. The first one written wins, which is the one on screen above.
                if (key.isEmpty() || value.isEmpty() || seen.stream().anyMatch(key.get()::sameJava)) continue;
                seen.add(key.get());
                ValueWire.compose(entryForm, List.of(key.get(), value.get())).ifPresent(pairs::add);
            }
            return ValueWire.compose(form, pairs);
        });
    }

    /**
     * A record the bot declares, as one labelled row per component, each drawn by {@link #editor}.
     *
     * <p><b>The components are fixed, so there is no Add and no ✕.</b> A list and a map are as long as the
     * user makes them, and a record is exactly as long as its own declaration — changing that is editing the
     * record, which is a thing to do in the file and not in a parameters window.
     */
    private static ValueEditors.Editor recordRows(ValueGrammar grammar, ValueTypes.BotClass declared,
                                                  Optional<SourceNode> written, BotRecords records,
                                                  ValueEditors.Context ctx) {
        List<BotRecords.Component> components = records.componentsOf(declared);
        List<ValueGrammar.Part> held = written.flatMap(node -> records.partsOf(declared, node.source()))
                .orElse(List.of());

        VBox column = composite();
        List<ValueEditors.Editor> readers = new ArrayList<>(components.size());
        for (int i = 0; i < components.size(); i++) {
            BotRecords.Component component = components.get(i);
            Optional<SourceNode> part = i < held.size() ? Optional.of(held.get(i).written()) : Optional.empty();
            Label name = new Label(component.name());
            name.getStyleClass().add("dialog-hint-text");
            name.setMinWidth(72);

            ValueEditors.Editor editor = editor(grammar, component.form(), part, records, ctx);
            readers.add(editor);

            HBox line = new HBox(6, name, editor.node());
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(editor.node(), Priority.ALWAYS);
            column.getChildren().add(line);
        }
        if (components.isEmpty()) column.getChildren().add(hint("This record has no components."));

        // Every component or none: compose declines a record with a component missing.
        return new ValueEditors.Editor(column, () -> records.compose(declared, readers.stream()
                .map(editor -> editor.read().get().orElse(null))
                .toList()));
    }

    /** One entry's two halves as the Java they are written as, or empty for a row just added. */
    private record Entry(Optional<SourceNode> key, Optional<SourceNode> value) {}

    /** Builds one map row's two editors. */
    private interface Factory {
        Cell cell(Entry entry);
    }

    /**
     * One map row, with the key watching for a key another row already has — on the key's focus leaving,
     * which for a key drawn as several controls is the whole group losing it.
     */
    private static Cell watched(List<Cell> cells, Cell cell) {
        cell.key().node().focusWithinProperty().addListener((o, was, is) -> {
            if (!is) markDuplicates(cells);
        });
        return cell;
    }

    /**
     * Marks every map row whose key a row above it already has.
     *
     * <p>Compared as the <b>tree</b> each key is written as, which is what {@code Map.ofEntries} is handed:
     * two rows writing one key are one key, whatever either field displays.
     */
    private static void markDuplicates(List<Cell> cells) {
        List<JavaValue> seen = new ArrayList<>();
        for (Cell cell : cells) {
            Optional<JavaValue> key = cell.key().read().get();
            boolean clash = key.isPresent() && seen.stream().anyMatch(key.get()::sameJava);
            if (!clash) key.ifPresent(seen::add);
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
     * Dresses one choice's toggle: text for a string or an enum constant (the choice is its words), the
     * type's own picture for everything else. The author's text is the label only when nothing can draw the
     * value — an option the leaf cannot read, or a type no plugin draws.
     */
    private static void showOption(ButtonBase toggle, Type leaf, String option, Optional<JavaValue> written,
                                   ValueEditors.Context ctx) {
        if (!ValueTypes.isLeaf(leaf)) {
            // One of several whole lists: its items, in a line, rather than the Java that builds it.
            toggle.setGraphic(null);
            toggle.setText(written.map(w -> itemsOf(leaf, w)).orElse(option));
            return;
        }
        boolean words = leaf == String.class || (leaf instanceof Class<?> cls && cls.isEnum());
        Node picture = words ? null
                : written.map(w -> ValueEditors.optionDisplay(leaf, w.source(), ctx)).orElse(null);
        toggle.setGraphic(picture);
        toggle.setText(picture == null ? option : "");
    }

    /**
     * A declared choice as the Java a field of {@code leaf} takes, or empty when the author's text is not a
     * value of that type.
     *
     * <p>The text is read as {@code leaf} first — {@code 10}, {@code UP}, {@code "Mining"} — and a
     * {@code String} leaf also takes the bare word, since {@code options = {"Mining"}} is how a person writes
     * a choice of text. Either way it is then written by the grammar, so a choice and a stored value that
     * mean the same thing are spelled the same and compare equal.
     */
    static Optional<JavaValue> optionSource(ValueGrammar grammar, Type leaf, String option) {
        if (option == null || option.isBlank()) return Optional.empty();
        Optional<Object> value = grammar.valueOf(leaf, option);
        if (value.isEmpty() && leaf == String.class) {
            value = Optional.of(option);
        }
        return value.flatMap(v -> grammar.spell(leaf, v));
    }

    /**
     * {@code written} as the grammar would write the value it holds — what a choice is compared against — or
     * the expression kept as it stands when the grammar cannot read it.
     */
    private static Optional<JavaValue> canonical(ValueGrammar grammar, Type leaf, Optional<SourceNode> written) {
        return written.map(node -> grammar.valueOf(leaf, node)
                .flatMap(value -> grammar.spell(leaf, value))
                .orElse(JavaValue.kept(node)));
    }

    /** A container's items as they are written, in a line: {@code [1, 2]}, or {@code [ ]} for none. */
    static String itemsOf(Type form, JavaValue written) {
        List<ValueGrammar.Part> parts = ValueWire.partsOrNone(form, written.source());
        if (parts.isEmpty()) return "[ ]";
        return parts.stream().map(part -> part.written().source())
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    // --- small helpers ------------------------------------------------------------------------------------

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
