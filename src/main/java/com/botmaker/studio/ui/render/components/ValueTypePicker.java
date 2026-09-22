package com.botmaker.studio.ui.render.components;

import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.ValueWire;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueForm;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the type of a <em>project variable</em>: a {@link ValueForm} — one type the loaded plugins declare,
 * wrapped in as many host containers as the cap allows.
 *
 * <p><b>Why this is not {@link BotTypePicker}.</b> The two pickers answer questions that only looked alike.
 * A signature's type is a name javac has to accept, drawn from a list Studio itself curates, and its axis has
 * two positions — {@code T} and {@code List<T>}. A stored variable's type is written into the bot's own
 * source, drawn from whatever the project's plugins declare, and it is a <em>tree</em>: splitting them is
 * what lets the second one grow a `Map<String, Duration>` without the first learning anything.
 *
 * <p><b>Wrap, rather than a shape.</b> What is left of the old shape axis is the type question: wrap the form
 * in a container, or take one off.
 *
 * <p><b>Two containers deep, and no further.</b> {@code docs/refactor/32-generic-values.md} caps the picker
 * rather than the model: a three-level value cell is not drawable in a table row, while a form read out of a
 * user's file is displayed at any depth. So the third wrap is not offered, and a deeper form read from the
 * file keeps its spelling on the button and may still be unwrapped.
 *
 * <p><b>The list is built at construction, from the plugins bound right now</b> — exactly the types some
 * loaded plugin declares, in plugin order. There is no grouping: a declared type carries no group since
 * 2026-09-22, and a menu of a dozen names needs none.
 */
public final class ValueTypePicker extends MenuButton {

    /** How many containers a picked form may carry. Read the file's javadoc before changing it. */
    private static final int MAX_DEPTH = 2;

    /** What a fresh picker and a new map key start as. */
    private static final ValueForm TEXT = ValueForm.of(String.class);

    private final ObjectProperty<ValueForm> form = new SimpleObjectProperty<>();
    private final List<MenuItem> wraps = new ArrayList<>();
    private final MenuItem unwrap = new MenuItem("Unwrap");

    public ValueTypePicker() {
        getStyleClass().add("bot-type-picker");
        setMaxWidth(Double.MAX_VALUE);

        getItems().add(wrapMenu());
        getItems().add(new SeparatorMenuItem());
        getItems().addAll(typeItems());

        form.addListener((obs, old, now) -> {
            setText(now == null ? "Choose a type…" : now.sourceName());
            refreshWraps(now);
        });
        form.set(TEXT);
    }

    /** One item per declared type, labelled the way the rest of the editor labels it. */
    private List<MenuItem> typeItems() {
        List<MenuItem> items = new ArrayList<>();
        for (PluginType<?> type : PluginHost.grammar().types()) {
            String qualified;
            try {
                qualified = JavaNames.canonical(type.type());
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (qualified.isBlank()) continue;
            items.add(typeItem(qualified));
        }
        return items;
    }

    /**
     * The label a menu shows — "Whole number" for {@code int}, a class's own simple name for everything
     * {@link BotType} does not name.
     */
    static String label(String qualified) {
        String simple = JavaNames.simple(qualified);
        for (BotType offered : BotType.values()) {
            if (JavaNames.simple(offered.typeName()).equals(simple)) return offered.label();
        }
        return simple;
    }

    /** The container axis as a menu of its own, above the type tree: every container, plus off. */
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
     * makes afterwards. Wrapping a {@code Duration} therefore offers {@code Map<String, Duration>}, which is
     * the map people write.
     */
    private static ValueForm wrapped(ValueContainer<?> container, ValueForm inner) {
        List<ValueForm> arguments = new ArrayList<>();
        for (int i = 0; i < container.arity() - 1; i++) arguments.add(TEXT);
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
    private MenuItem typeItem(String qualified) {
        MenuItem item = new MenuItem(label(qualified));
        item.setOnAction(e -> form.set(withLeaf(form.get(), qualified)));
        return item;
    }

    /** {@code form} with its innermost leaf replaced — the form itself when it is one. */
    private static ValueForm withLeaf(ValueForm current, String qualified) {
        if (current instanceof ValueForm.Of of) {
            List<ValueForm> arguments = new ArrayList<>(of.arguments());
            arguments.set(arguments.size() - 1, withLeaf(of.last(), qualified));
            return new ValueForm.Of(of.container(), arguments);
        }
        return ValueForm.of(qualified);
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
