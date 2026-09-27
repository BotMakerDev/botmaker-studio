package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.palette.TypeNames;
import com.botmaker.studio.plugin.ValueWire;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueTypes;
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
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
 * <p><b>A pick changes one part of the type (feedback 3, 2026-09-27)</b>: the part selected in the header,
 * where every name — a container's included — can be clicked. It starts on the innermost last argument, the
 * part a pick always changed, so choosing a different element does not throw away a list just asked for; a
 * click on a map's key is how the key is changed ({@link TypePath}). Wrap wraps the selected part, and Unwrap
 * unwraps the container at or around it.
 *
 * <p><b>The menu stays open while the type is put together (2026-09-27)</b> — wrap, pick a leaf, unwrap — and
 * shows the type so far in its header. It is committed once, when the menu closes (Done, a double-click on a
 * type, Enter in the search field, or a click outside); Escape closes it with nothing changed. One commit rather than one per click
 * because a caller may rebuild the row this button sits in on a change, and a menu left open over a replaced
 * row goes nowhere.
 *
 * <p>A {@link ContextMenu} rather than a hand-made popup, so it is styled, themed and keyboard-driven exactly
 * as every other menu in the editor.
 */
public final class TypeChooser extends Button {

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

    /** The type being put together and the part of it a pick changes; each change of one is checked against the other. */
    private static final class Draft {
        final ObjectProperty<Type> type;
        final ObjectProperty<TypePath> part;

        Draft(Type start) {
            type = new SimpleObjectProperty<>(start);
            part = new SimpleObjectProperty<>(TypePath.innermost(start));
        }

        /** The selected part, cut back to what the type still has. */
        TypePath part() {
            return part.get().within(type.get());
        }

        void set(Type next, TypePath selected) {
            part.set(selected.within(next));
            type.set(next);
        }

        void pick(Type picked) {
            Type next = part().replace(type.get(), picked);
            set(next, part());
        }
    }

    /** Opens the menu under the button; answers it, so a test can drive the rows. */
    ContextMenu open() {
        TypeCatalog offered = catalog.get();
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("type-chooser-menu");
        // The type being put together; committed when the menu closes, unless Escape closed it.
        Draft draft = new Draft(type.get());
        boolean[] cancelled = {false};

        TextField search = new TextField();
        search.setPromptText("Search types");
        menu.getItems().add(headerRow(menu, draft, search));
        CustomMenuItem searchItem = new CustomMenuItem(search);
        searchItem.setHideOnClick(false);
        menu.getItems().add(searchItem);
        menu.getItems().add(wrapRow(draft));

        int body = menu.getItems().size();
        List<TypeCatalog.Entry> shown = new ArrayList<>();
        List<CustomMenuItem> rows = new ArrayList<>();
        // Rebuilt only when the search changes. A pick keeps every row and moves the highlight: rows replaced
        // under the pointer have seen no mouse-enter, so the next click on one only armed it (feedback 3).
        Runnable rebuild = () -> {
            menu.getItems().remove(body, menu.getItems().size());
            shown.clear();
            rows.clear();
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
                    CustomMenuItem row = entryItem(menu, entry, draft);
                    menu.getItems().add(row);
                    rows.add(row);
                    shown.add(entry);
                }
            }
            highlight(rows, shown, draft);
        };
        rebuild.run();
        search.textProperty().addListener((o, was, now) -> rebuild.run());
        draft.type.addListener((o, was, now) -> highlight(rows, shown, draft));
        draft.part.addListener((o, was, now) -> highlight(rows, shown, draft));
        // Enter in the search field takes the first match and closes: typing "poi" and Enter is the whole gesture.
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !shown.isEmpty()) {
                draft.pick(shown.getFirst().type());
                menu.hide();
                e.consume();
            }
        });
        menu.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancelled[0] = true;
        });
        menu.setOnHidden(e -> {
            Type chosen = draft.type.get();
            if (!cancelled[0] && chosen != null && !chosen.equals(type.get())) type.set(chosen);
        });
        menu.setOnShown(e -> search.requestFocus());
        menu.show(this, Side.BOTTOM, 0, 0);
        return menu;
    }

    /**
     * <i>Done</i>, then the type so far. Done leads, full size and as tall as the search field under it: at the
     * far end of the row, small, it was a long way from the list it closes (feedback 3). The type is one
     * clickable name per part, the selected one marked: that is the part a pick changes.
     */
    private MenuItem headerRow(ContextMenu menu, Draft draft, TextField search) {
        Button done = new Button("Done");
        done.getStyleClass().add("type-chooser-done");
        done.setDefaultButton(true);
        done.setMinWidth(80);
        done.prefHeightProperty().bind(search.heightProperty());
        done.setOnAction(e -> menu.hide());
        HBox parts = new HBox();
        parts.setAlignment(Pos.CENTER_LEFT);
        Runnable spell = () -> spellParts(parts, draft);
        draft.type.addListener((o, was, now) -> spell.run());
        draft.part.addListener((o, was, now) -> spell.run());
        spell.run();
        HBox row = new HBox(8, done, parts);
        row.setAlignment(Pos.CENTER_LEFT);
        CustomMenuItem item = new CustomMenuItem(row);
        item.setHideOnClick(false);
        item.getStyleClass().add("type-chooser-current");
        return item;
    }

    /** The draft as its {@link TypePath#segments}: a name selects its part, punctuation is only read. */
    private static void spellParts(HBox parts, Draft draft) {
        parts.getChildren().clear();
        Type now = draft.type.get();
        if (now == null) {
            parts.getChildren().add(new Label("No type yet"));
            return;
        }
        TypePath selected = draft.part();
        for (TypePath.Segment segment : TypePath.segments(now)) {
            Label text = new Label(segment.text());
            text.getStyleClass().add(segment.path() == null ? "type-path-mark" : "type-path-part");
            if (segment.path() != null) {
                if (segment.path().equals(selected)) text.getStyleClass().add("type-path-selected");
                text.setTooltip(new Tooltip("Click, then pick a type to change this part"));
                text.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                    if (e.getButton() != MouseButton.PRIMARY) return;
                    draft.part.set(segment.path());
                    e.consume();
                });
            }
            parts.getChildren().add(text);
        }
    }

    /** The row of the selected part's type carries the highlight; every other row loses it. */
    private static void highlight(List<CustomMenuItem> rows, List<TypeCatalog.Entry> shown, Draft draft) {
        Type part = draft.type.get() == null ? null : draft.part().at(draft.type.get());
        for (int i = 0; i < rows.size(); i++) {
            List<String> classes = rows.get(i).getStyleClass();
            boolean current = shown.get(i).type().equals(part);
            if (current && !classes.contains("type-chooser-current")) classes.add("type-chooser-current");
            if (!current) classes.remove("type-chooser-current");
        }
    }

    /** One type: a click picks it and keeps the menu open, a double-click picks it and closes. */
    private CustomMenuItem entryItem(ContextMenu menu, TypeCatalog.Entry entry, Draft draft) {
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
        item.setOnAction(e -> draft.pick(entry.type()));
        row.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) return;
            draft.pick(entry.type());
            menu.hide();
        });
        return item;
    }

    /**
     * <i>Wrap in</i> — one button per container — and <i>Unwrap</i>, on one row, over the selected part. A
     * wrap moves the selection inside the new container, so the next pick changes what it holds.
     */
    private MenuItem wrapRow(Draft draft) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Wrap in");
        title.getStyleClass().add("type-chooser-hint");
        row.getChildren().add(title);
        for (ValueContainer<?> container : ValueWire.containers()) {
            Button wrap = new Button(container.label());
            wrap.getStyleClass().add("type-chooser-wrap");
            wrap.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> !wrappable(draft), draft.type, draft.part));
            wrap.setOnAction(e -> {
                TypePath part = draft.part();
                Type next = part.wrap(draft.type.get(), container);
                draft.set(next, part.then(TypePath.innermost(part.at(next))));
            });
            row.getChildren().add(wrap);
        }
        Button unwrap = new Button("Unwrap");
        unwrap.getStyleClass().add("type-chooser-wrap");
        unwrap.disableProperty().bind(Bindings.createBooleanBinding(
                () -> draft.type.get() == null || draft.part().container(draft.type.get()) == null,
                draft.type, draft.part));
        unwrap.setOnAction(e -> {
            TypePath around = draft.part().container(draft.type.get());
            if (around != null) draft.set(around.unwrap(draft.type.get()), around);
        });
        row.getChildren().add(unwrap);
        CustomMenuItem item = new CustomMenuItem(row);
        item.setHideOnClick(false);
        return item;
    }

    private static boolean wrappable(Draft draft) {
        Type now = draft.type.get();
        Type part = now == null ? null : draft.part().at(now);
        return part != null && part != void.class && !(part instanceof ValueTypes.Unknown);
    }
}
