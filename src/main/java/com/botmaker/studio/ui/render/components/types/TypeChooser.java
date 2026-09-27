package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.palette.TypeNames;
import com.botmaker.studio.plugin.ValueWire;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The one control a type is chosen with — a parameter, a local variable, a function's inputs and its result
 * (2026-09-26). A button naming the type as Java writes it; pressed, a menu with a search field, a
 * <i>Wrap in</i> row and the {@link TypeCatalog}'s groups.
 *
 * <p><b>Wrap is for every type, at any depth.</b> {@code List<Map<String, Point>>} is a type like any other;
 * the two-container cap the Parameters window had was a limit of its value cells, not of types, and it is the
 * cells that must draw what they are given.
 *
 * <p><b>Picking a type replaces the leaf and keeps the containers</b>, so choosing a different element does
 * not throw away a list just asked for.
 *
 * <p><b>The menu stays open while the type is put together (2026-09-27)</b> — wrap, pick a leaf, unwrap — and
 * shows the type so far in its header. It is committed once, when the menu closes (Done, Enter in the search
 * field, or a click outside); Escape closes it with nothing changed. One commit rather than one per click
 * because a caller may rebuild the row this button sits in on a change, and a menu left open over a replaced
 * row goes nowhere.
 *
 * <p>A {@link ContextMenu} rather than a hand-made popup, so it is styled, themed and keyboard-driven exactly
 * as every other menu in the editor.
 */
public final class TypeChooser extends Button {

    /** What a map's key, and anything else wrapped around a type besides it, starts as. */
    private static final Type TEXT = String.class;

    private final ObjectProperty<Type> type = new SimpleObjectProperty<>();
    private final Supplier<TypeCatalog> catalog;

    /**
     * @param catalog asked each time the menu opens, so a plugin installed or a record added since the
     *                window opened is offered
     */
    public TypeChooser(Supplier<TypeCatalog> catalog) {
        this.catalog = catalog;
        getStyleClass().addAll("bot-type-picker", "type-chooser");
        setMaxWidth(Double.MAX_VALUE);
        setAlignment(Pos.CENTER_LEFT);
        type.addListener((obs, old, now) -> {
            setText(now == null ? "Choose a type…" : label(now));
            setTooltip(now == null ? null : new Tooltip(ValueTypes.sourceName(now)));
        });
        setText("Choose a type…");
        setOnAction(e -> open());
    }

    public ObjectProperty<Type> typeProperty() {
        return type;
    }

    public Type type() {
        return type.get();
    }

    public void setType(Type value) {
        type.set(value);
    }

    /** How the button names a type: Java's own spelling, with every class by its simple name. */
    static String label(Type type) {
        return TypeNames.label(type);
    }

    // ---- the menu -------------------------------------------------------------------------------------------

    private void open() {
        TypeCatalog offered = catalog.get();
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("type-chooser-menu");
        // The type being put together; committed when the menu closes, unless Escape closed it.
        ObjectProperty<Type> draft = new SimpleObjectProperty<>(type.get());
        boolean[] cancelled = {false};

        menu.getItems().add(headerRow(menu, draft));
        TextField search = new TextField();
        search.setPromptText("Search types");
        CustomMenuItem searchItem = new CustomMenuItem(search);
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);
        menu.getItems().add(wrapRow(draft));

        int body = menu.getItems().size();
        List<TypeCatalog.Entry> shown = new ArrayList<>();
        Runnable rebuild = () -> {
            menu.getItems().remove(body, menu.getItems().size());
            shown.clear();
            List<TypeCatalog.Group> groups = offered.filter(search.getText());
            if (groups.isEmpty()) {
                MenuItem none = new MenuItem("No type matches");
                none.setDisable(true);
                menu.getItems().add(none);
            }
            for (TypeCatalog.Group group : groups) {
                MenuItem header = new MenuItem(group.title());
                header.setDisable(true);
                header.getStyleClass().add("block-section-header");
                menu.getItems().add(header);
                for (TypeCatalog.Entry entry : group.entries()) {
                    menu.getItems().add(entryItem(entry, draft));
                    shown.add(entry);
                }
            }
        };
        rebuild.run();
        search.textProperty().addListener((o, was, now) -> rebuild.run());
        // The current leaf's highlight follows the draft — after the click that changed it has finished, since
        // the rebuild removes the very item being clicked.
        draft.addListener((o, was, now) -> Platform.runLater(rebuild));
        // Enter in the search field takes the first match and closes: typing "poi" and Enter is the whole gesture.
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !shown.isEmpty()) {
                draft.set(withLeaf(draft.get(), shown.getFirst().type()));
                menu.hide();
                e.consume();
            }
        });
        menu.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancelled[0] = true;
        });
        menu.setOnHidden(e -> {
            if (!cancelled[0] && draft.get() != null && !draft.get().equals(type.get())) type.set(draft.get());
        });
        menu.setOnShown(e -> search.requestFocus());
        menu.show(this, Side.BOTTOM, 0, 0);
    }

    /** The type so far, and <i>Done</i>. */
    private MenuItem headerRow(ContextMenu menu, ObjectProperty<Type> draft) {
        Label now = new Label();
        now.getStyleClass().add("type-chooser-name");
        now.textProperty().bind(Bindings.createStringBinding(
                () -> draft.get() == null ? "No type yet" : label(draft.get()), draft));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button done = new Button("Done");
        done.getStyleClass().add("type-chooser-wrap");
        done.setOnAction(e -> menu.hide());
        HBox row = new HBox(8, now, gap, done);
        row.setAlignment(Pos.CENTER_LEFT);
        CustomMenuItem item = new CustomMenuItem(row);
        item.setHideOnClick(false);
        item.getStyleClass().add("type-chooser-current");
        return item;
    }

    private MenuItem entryItem(TypeCatalog.Entry entry, ObjectProperty<Type> draft) {
        Label name = new Label(entry.label());
        name.getStyleClass().add("type-chooser-name");
        Label hint = new Label(entry.hint());
        hint.getStyleClass().add("type-chooser-hint");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(16, name, gap, hint);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(220);
        CustomMenuItem item = new CustomMenuItem(row);
        item.setHideOnClick(false);
        if (entry.type().equals(innermost(draft.get()))) item.getStyleClass().add("type-chooser-current");
        item.setOnAction(e -> draft.set(withLeaf(draft.get(), entry.type())));
        return item;
    }

    /** <i>Wrap in</i> — one button per container — and <i>Unwrap</i>, on one row, over the type so far. */
    private MenuItem wrapRow(ObjectProperty<Type> draft) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Wrap in");
        title.getStyleClass().add("type-chooser-hint");
        row.getChildren().add(title);
        for (ValueContainer<?> container : ValueWire.containers()) {
            Button wrap = new Button(container.label());
            wrap.getStyleClass().add("type-chooser-wrap");
            wrap.disableProperty().bind(Bindings.createBooleanBinding(() -> !wrappable(draft.get()), draft));
            wrap.setOnAction(e -> draft.set(wrapped(container, draft.get())));
            row.getChildren().add(wrap);
        }
        Button unwrap = new Button("Unwrap");
        unwrap.getStyleClass().add("type-chooser-wrap");
        unwrap.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !(draft.get() instanceof ValueTypes.Parameterized), draft));
        unwrap.setOnAction(e -> {
            if (draft.get() instanceof ValueTypes.Parameterized p) draft.set(p.last());
        });
        row.getChildren().add(unwrap);
        CustomMenuItem item = new CustomMenuItem(row);
        item.setHideOnClick(false);
        return item;
    }

    private static boolean wrappable(Type now) {
        return now != null && now != void.class && !(now instanceof ValueTypes.Unknown);
    }

    /** The type at the bottom of every container: {@code Point} in {@code List<Map<String, Point>>}. */
    static Type innermost(Type type) {
        return type instanceof ValueTypes.Parameterized p ? innermost(p.last()) : type;
    }

    /**
     * {@code inner} inside {@code container}: the last argument, with any earlier ones text. The last because
     * that is what a container holds — a list's element, a map's value — so wrapping a {@code Duration} in a
     * map gives {@code Map<String, Duration>}, the map people write.
     */
    static Type wrapped(ValueContainer<?> container, Type inner) {
        List<Type> arguments = new ArrayList<>();
        for (int i = 0; i < container.arity() - 1; i++) arguments.add(TEXT);
        arguments.add(inner);
        return ValueTypes.of(container, arguments);
    }

    /** {@code current} with its innermost leaf replaced — {@code leaf} itself when it is one. */
    static Type withLeaf(Type current, Type leaf) {
        // void cannot sit inside a container, so picking it drops the containers.
        if (current instanceof ValueTypes.Parameterized p && leaf != void.class) {
            List<Type> arguments = new ArrayList<>(p.arguments());
            arguments.set(arguments.size() - 1, withLeaf(p.last(), leaf));
            return new ValueTypes.Parameterized(p.raw(), arguments);
        }
        return leaf;
    }
}
