package com.botmaker.studio.ui.render.components;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.plugin.ValueWire;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the type of a <em>project variable</em>: a {@link ValueForm} — one {@link ValueType} the loaded
 * plugins register, wrapped in as many registered {@linkplain ValueContainer containers} as the cap allows.
 *
 * <p><b>Why this is not {@link BotTypePicker}.</b> The two pickers answer questions that only looked alike.
 * A signature's type is a name javac has to accept, drawn from a list Studio itself curates, and its axis has
 * two positions — {@code T} and {@code List<T>}. A stored variable's type is written into the bot's own
 * source, drawn from whatever the project's plugins registered, and it is a <em>tree</em>: splitting them is
 * what lets the second one grow a `Map<String, Duration>` without the first learning anything.
 *
 * <p><b>Wrap, rather than a shape.</b> {@code Shape ▸} used to offer four constants, two of which said
 * something about the value ("one of…", "any of…") and not about the type at all — the choices a value may
 * come from are the row's, and a row carries them. What is left is the type question: wrap the form in a
 * container, or take one off. The menu is built from {@link ValueCatalog#containers()}, so a plugin's own
 * container is offered here the day it registers one, with no Studio release.
 *
 * <p><b>Two containers deep, and no further.</b> {@code docs/refactor/32-generic-values.md} caps the picker
 * rather than the model: a three-level value cell is not drawable in a table row, while a form read out of a
 * user's file is displayed at any depth. So the third wrap is not offered, and a deeper form read from the
 * file keeps its spelling on the button and may still be unwrapped.
 *
 * <p><b>The list is built at construction, from the plugins bound right now.</b> There is no static set to
 * enumerate — a project pinned to an SDK that never had a type simply never sees it — so the menu is a
 * function of {@link ValueWire#registered()} at the moment the dialog opens, which is also when the project
 * whose plugins answer it is the one open.
 *
 * <p><b>Grouping comes from the contract, not from here.</b> {@link ValueType#group()} is a free string a
 * plugin sets, so a second plugin files its types under a heading of its own without a constant being granted
 * to it; the <em>first</em> registration of a heading decides where that heading sits, which stops a plugin
 * reordering another's menu by naming it. A type with no group sits at the top level, in its registration
 * position.
 */
public final class ValueTypePicker extends MenuButton {

    /** How many containers a picked form may carry. Read the file's javadoc before changing it. */
    private static final int MAX_DEPTH = 2;

    private final ObjectProperty<ValueForm> form = new SimpleObjectProperty<>();
    private final List<MenuItem> wraps = new ArrayList<>();
    private final MenuItem unwrap = new MenuItem("Unwrap");

    public ValueTypePicker() {
        getStyleClass().add("bot-type-picker");
        setMaxWidth(Double.MAX_VALUE);

        getItems().add(wrapMenu());
        getItems().add(new SeparatorMenuItem());
        getItems().addAll(typeMenus());

        form.addListener((obs, old, now) -> {
            setText(now == null ? "Choose a type…" : now.sourceName());
            refreshWraps(now);
        });
        form.set(ValueForm.of(ValueWire.type(ValueCatalog.TEXT_ID)));
    }

    /**
     * The type tree, grouped by {@link ValueType#group()} in first-registration order.
     *
     * <p>An ungrouped type is added flat rather than under a "Other" heading nobody chose: the contract says
     * a blank group means the top level, and inventing a label for it here would be this host deciding
     * something the plugin declined to.
     */
    private List<MenuItem> typeMenus() {
        Map<String, List<ValueType>> grouped = new LinkedHashMap<>();
        for (ValueType type : ValueWire.registered()) {
            grouped.computeIfAbsent(type.group(), key -> new ArrayList<>()).add(type);
        }
        List<MenuItem> items = new ArrayList<>();
        for (Map.Entry<String, List<ValueType>> entry : grouped.entrySet()) {
            List<MenuItem> members = entry.getValue().stream().map(this::typeItem).toList();
            if (entry.getKey().isBlank()) {
                items.addAll(members);
            } else {
                Menu menu = new Menu(entry.getKey());
                menu.getItems().addAll(members);
                items.add(menu);
            }
        }
        return items;
    }

    /** The container axis as a menu of its own, above the type tree: every registered container, plus off. */
    private Menu wrapMenu() {
        Menu menu = new Menu("Wrap in");
        for (ValueContainer<?> container : ValueWire.containers()) {
            MenuItem item = new MenuItem(container.label());
            item.setOnAction(e -> {
                ValueForm now = form.get();
                if (now != null) form.set(wrapped(container, now));
            });
            wraps.add(item);
            menu.getItems().add(item);
        }
        unwrap.setOnAction(e -> {
            if (form.get() instanceof ValueForm.Of of) form.set(of.last());
        });
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(unwrap);
        return menu;
    }

    /**
     * {@code inner} inside {@code container}: the last argument, with any earlier ones defaulting to text.
     *
     * <p>The <em>last</em> argument because that is the one a container holds — a {@code List}'s element, a
     * {@code Map}'s value — and text for the rest because a map keyed by anything else is a choice the author
     * makes afterwards, by picking a type while the key position is the one being edited. Wrapping a
     * {@code Duration} therefore offers {@code Map<String, Duration>}, which is the map people write.
     */
    private static ValueForm wrapped(ValueContainer<?> container, ValueForm inner) {
        List<ValueForm> arguments = new ArrayList<>();
        for (int i = 0; i < container.arity() - 1; i++) {
            arguments.add(ValueForm.of(ValueWire.type(ValueCatalog.TEXT_ID)));
        }
        arguments.add(inner);
        return new ValueForm.Of(container, arguments);
    }

    /** Greys the wraps once the cap is reached, and {@code Unwrap} while there is nothing to take off. */
    private void refreshWraps(ValueForm now) {
        boolean capped = now == null || now.depth() >= MAX_DEPTH;
        for (MenuItem item : wraps) item.setDisable(capped);
        unwrap.setDisable(!(now instanceof ValueForm.Of));
        setTooltip(now == null ? null : new Tooltip(now.sourceName()));
    }

    /**
     * Picking a type replaces the <em>leaf</em> and keeps the containers around it, so choosing a different
     * element type does not throw away a list the author has just asked for.
     */
    private MenuItem typeItem(ValueType type) {
        MenuItem item = new MenuItem(type.label());
        item.setOnAction(e -> form.set(withLeaf(form.get(), type)));
        return item;
    }

    /** {@code form} with its innermost leaf replaced — the form itself when it is one. */
    private static ValueForm withLeaf(ValueForm current, ValueType type) {
        if (current instanceof ValueForm.Of of) {
            List<ValueForm> arguments = new ArrayList<>(of.arguments());
            arguments.set(arguments.size() - 1, withLeaf(of.last(), type));
            return new ValueForm.Of(of.container(), arguments);
        }
        return ValueForm.of(type);
    }

    public ObjectProperty<ValueForm> formProperty() {
        return form;
    }

    public ValueForm form() {
        return form.get();
    }

    public void setForm(ValueForm value) {
        form.set(value);
    }
}
