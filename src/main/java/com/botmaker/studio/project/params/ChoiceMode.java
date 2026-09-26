package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueTypes;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * How a parameter's value is picked: freely, one of a declared set, or any number of them.
 *
 * <p><b>A mode is not a type (2026-09-26).</b> The type is chosen once, and the mode sits beside it: a
 * {@code Point} typed freely, picked from three points, or ticked from three points is one type with three
 * modes. {@link #MANY} is the one mode that changes what the field is declared as — a set of ticks is a
 * {@code List<T>} — and the base type {@code T} is what the chooser, the choices and every mode speak of.
 *
 * <p><b>Read off the declaration, never stored.</b> Choices are written as {@code @Param(options = …)}, so a
 * field with none is {@link #NONE}, a leaf with some is {@link #ONE}, and a list of a leaf with some is
 * {@link #MANY}. A list with no choices is a list typed freely, which is {@code NONE} over {@code List<T>}.
 */
public enum ChoiceMode {

    NONE("none", "Any value"),
    ONE("one", "One of"),
    MANY("many", "Any of");

    private final String id;
    private final String displayName;

    ChoiceMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The mode a field declared as {@code form} with {@code options} is in. */
    public static ChoiceMode of(Type form, List<String> options) {
        if (options == null || options.isEmpty()) return NONE;
        return listElement(form).isPresent() ? MANY : ONE;
    }

    /**
     * The type every mode speaks of: the element of a ticked list, and the declared type otherwise — a free
     * {@code List<T>} is its own base, since nothing is picked from a set there.
     */
    public static Type base(Type form, List<String> options) {
        return of(form, options) == MANY ? listElement(form).orElse(form) : form;
    }

    /**
     * What the field is declared as in this mode, over {@code base}. A list holds boxes, so leaving
     * {@link #MANY} unboxes the element again: an {@code int} ticked and then picked is an {@code int}.
     */
    public Type formFor(Type base) {
        if (this == MANY) return ValueTypes.listOf(base);
        if (base instanceof Class<?> cls && !cls.isPrimitive()) {
            Class<?> primitive = java.lang.invoke.MethodType.methodType(cls).unwrap().returnType();
            if (primitive != cls) return primitive;
        }
        return base;
    }

    /**
     * The modes {@code base} can be put in, {@link #NONE} always first. Choices are values of a single leaf
     * the grammar can read, so a container or a type nobody declares has none; an enum brings its own set.
     * {@link #ONE} over a flag is a flag with its two values written out again, so a flag is free or ticked.
     */
    public static List<ChoiceMode> offered(Type base, boolean known) {
        if (!ValueTypes.isLeaf(base) || !known) return List.of(NONE);
        if (base instanceof Class<?> cls && cls.isEnum()) return List.of(NONE);
        if (base == boolean.class || base == Boolean.class) return List.of(NONE, MANY);
        return List.of(NONE, ONE, MANY);
    }

    private static Optional<Type> listElement(Type form) {
        if (ValueTypes.container(form).orElse(null) != ValueContainer.LIST) return Optional.empty();
        Type element = ValueTypes.arguments(form).getLast();
        return ValueTypes.isLeaf(element) ? Optional.of(element) : Optional.empty();
    }
}
