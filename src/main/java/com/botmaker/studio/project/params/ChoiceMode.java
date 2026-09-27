package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
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
 * field with none is {@link #NONE}, and one with some is {@link #ONE} or {@link #MANY}. <b>The choices' own
 * shape tells those two apart (2026-09-27)</b>: over a {@code List<X>}, a choice that reads as the list is one
 * of several lists, and a choice that reads as an {@code X} is a tick. A list with no choices is a list typed
 * freely, which is {@code NONE} over {@code List<T>}. <b>An enum is the exception</b>: with no choices it is
 * {@code ONE} over every constant, and a list of it {@code MANY} — every constant ticked is written as no
 * choices, since the two mean the same.
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
    public static ChoiceMode of(Type form, List<String> options, ValueGrammar grammar) {
        if (options == null || options.isEmpty()) {
            // An enum has no free mode: with nothing written down it is picked from every constant.
            return closedSet(form, grammar).map(constants -> form == constants ? ONE : MANY).orElse(NONE);
        }
        if (ValueTypes.isLeaf(form) || listElement(form).isEmpty()) return ONE;
        return options.stream().anyMatch(option -> reads(grammar, form, option)) ? ONE : MANY;
    }

    /**
     * The type every mode speaks of: the element of a ticked list, and the declared type otherwise — a free
     * {@code List<T>}, and one picked from several lists, are their own base.
     */
    public static Type base(Type form, List<String> options, ValueGrammar grammar) {
        return of(form, options, grammar) == MANY ? listElement(form).orElse(form) : form;
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
     * The modes {@code base} can be put in, {@link #NONE} first wherever it is one. Choices are values the
     * grammar can read, so a type nobody declares has none. An enum is its own set, so it is one of its
     * constants or several of them and never "any value" (feedback 3). {@link #ONE} over a flag is a flag
     * with its two values written out again, so a flag is free or ticked. A container can be one of several
     * of its own values, and is never ticked — ticks over a list would be a list of lists.
     */
    public static List<ChoiceMode> offered(Type base, boolean known) {
        if (!known) return List.of(NONE);
        if (!ValueTypes.isLeaf(base)) {
            return ValueTypes.container(base).isPresent() ? List.of(NONE, ONE) : List.of(NONE);
        }
        if (base instanceof Class<?> cls && cls.isEnum()) return List.of(ONE, MANY);
        if (base == boolean.class || base == Boolean.class) return List.of(NONE, MANY);
        return List.of(NONE, ONE, MANY);
    }

    /**
     * {@code row} declared with {@code options}. Choices are the limit on what can be picked, so declaring any
     * drops the range: a bound hidden under a set of choices would still clamp a value nobody can see why
     * (feedback 3, 2026-09-27). No choices keeps the range.
     */
    public static ParameterRow declare(ParameterRow row, List<String> options) {
        ParameterRow.Builder next = row.toBuilder().options(options);
        if (options != null && !options.isEmpty()) {
            next.bounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        return next.build();
    }

    /**
     * The choices of {@code options} that are still values once the field is declared as {@code form} — of
     * the form itself, or of its element when it is a list. Read strictly, as Java: {@code "1"} is no
     * {@code String}, so an {@code int}'s choices do not survive becoming text.
     */
    public static List<String> kept(ValueGrammar grammar, Type form, List<String> options) {
        Optional<Type> element = listElement(form);
        return options.stream()
                .filter(option -> reads(grammar, form, option)
                        || element.map(type -> reads(grammar, type, option)).orElse(false))
                .toList();
    }

    /**
     * The choices an enum's strip writes when {@code ticked} are on: the ticked names in the enum's own order,
     * or none when every constant is ticked — which is what no choices means for an enum, and the shorter Java.
     */
    public static List<String> enumOptions(Class<?> constants, java.util.Collection<String> ticked) {
        List<String> names = names(constants);
        List<String> kept = names.stream().filter(ticked::contains).toList();
        return kept.size() == names.size() ? List.of() : kept;
    }

    /** The constants an enum's {@code options} leave on: every one when none are written down. */
    public static List<String> ticked(Class<?> constants, List<String> options) {
        return options == null || options.isEmpty() ? names(constants) : options;
    }

    /**
     * The mode a field in {@code mode} keeps when it is retyped to {@code base}. {@code written} says whether
     * its choices were written down: an enum's every-constant is not, and a choice nobody made does not
     * survive the enum — an enum picked from all its days becomes a free int, not one of {0}. An enum keeps
     * its mode from another enum either way. A mode the new type cannot be in falls to its first.
     */
    public static ChoiceMode afterRetype(ChoiceMode mode, boolean written, Type base, boolean known) {
        List<ChoiceMode> offered = offered(base, known);
        boolean closed = base instanceof Class<?> cls && cls.isEnum();
        return offered.contains(mode) && (written || closed || mode == NONE) ? mode : offered.getFirst();
    }

    /** The largest enum whose constants are drawn as one strip of toggles; above it, rows of its own picker. */
    public static final int TOGGLES = 16;

    /** Whether {@code type} is an enum small enough for a strip of toggles. */
    public static boolean toggled(Type type) {
        return type instanceof Class<?> cls && cls.isEnum() && cls.getEnumConstants().length <= TOGGLES;
    }

    private static List<String> names(Class<?> constants) {
        return java.util.Arrays.stream(constants.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList();
    }

    /** The enum {@code form} picks from with no choices written down: itself, or a list's element. */
    private static Optional<Class<?>> closedSet(Type form, ValueGrammar grammar) {
        Type element = form instanceof Class<?> ? form : listElement(form).orElse(null);
        return element instanceof Class<?> cls && cls.isEnum() && grammar.known(cls)
                ? Optional.of(cls) : Optional.empty();
    }

    private static boolean reads(ValueGrammar grammar, Type type, String option) {
        return option != null && !option.isBlank() && grammar.valueOf(type, option).isPresent();
    }

    private static Optional<Type> listElement(Type form) {
        if (ValueTypes.container(form).orElse(null) != ValueContainer.LIST) return Optional.empty();
        Type element = ValueTypes.arguments(form).getLast();
        return ValueTypes.isLeaf(element) ? Optional.of(element) : Optional.empty();
    }
}
