package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.JdkLiterals;
import com.botmaker.studio.plugin.grammar.ValueGrammar;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What Studio draws for a value no plugin draws (6f, 2026-09-27), decided without a screen. A plugin always
 * wins; this is asked only after every claimant answered nothing.
 *
 * <p>An unreadable source is always {@link Kept}: offering it as an editable shape would overwrite what the
 * author wrote on the first touch. Only an explicit Reset replaces it.
 */
sealed interface FallbackShape {

    /** Parts nested deeper than this are kept as written — a type whose part is itself stops here. */
    int MAX_DEPTH = 4;

    /** An enum: its constants, and the one held when the source reads as one. */
    record Choice(Class<?> enumType, List<Object> constants, Optional<Object> held) implements FallbackShape {}

    /** A declared call: one row per component, each drawn as its own type. */
    record Parts(ComponentType<?> component, List<Class<?>> types, List<Object> values, List<String> labels)
            implements FallbackShape {}

    /** A JDK literal with no plugin drawing it — a project without plugin-basics. */
    record Literal(Class<?> type, String shown) implements FallbackShape {}

    /** Anything else: the source as written, and the fresh value Reset writes when there is one. */
    record Kept(String source, Optional<JavaValue> fresh) implements FallbackShape {}

    static FallbackShape of(ValueGrammar grammar, Type leaf, String source, int depth) {
        String written = source == null ? "" : source.trim();
        Optional<Object> value = written.isEmpty() ? Optional.empty() : grammar.valueOf(leaf, written);
        if (value.isEmpty() && !written.isEmpty() && leaf instanceof Class<?> cls && cls.isEnum()) {
            value = constant(cls, written);
        }
        if (!written.isEmpty() && value.isEmpty()) return kept(grammar, leaf, written);
        if (leaf instanceof Class<?> cls && cls.isEnum()) {
            return new Choice(cls, List.of((Object[]) cls.getEnumConstants()), value);
        }
        if (leaf instanceof Class<?> cls && JdkLiterals.handles(cls)) {
            return new Literal(cls, written);
        }
        if (value.isPresent() && depth < MAX_DEPTH) {
            Optional<ComponentType<?>> component = grammar.componentOf(value.get());
            if (component.isPresent()) {
                Optional<Parts> parts = parts(component.get(), value.get());
                if (parts.isPresent()) return parts.get();
            }
        }
        return kept(grammar, leaf, written);
    }

    /** {@code value} written as {@code leaf}, fully qualified — what a fallback hands back. */
    static Optional<JavaValue> write(ValueGrammar grammar, Type leaf, Object value) {
        return grammar.initializer(leaf, value);
    }

    /** {@code parts} built again from {@code values} and written as {@code leaf}; empty when the build declines. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Optional<JavaValue> rebuild(ValueGrammar grammar, Type leaf, Parts parts, List<Object> values) {
        Object built;
        try {
            built = ((ComponentType) parts.component()).build(values);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        return built == null ? Optional.empty() : write(grammar, leaf, built);
    }

    /** What a person typed into a literal field, read as {@code type}; empty when it is not one. */
    static Optional<Object> literal(Class<?> type, String typed) {
        String text = typed == null ? "" : typed;
        if (type == String.class) return Optional.of(text);
        String t = text.trim();
        try {
            if (type == int.class || type == Integer.class) return Optional.of(Integer.parseInt(t));
            if (type == long.class || type == Long.class) return Optional.of(Long.parseLong(t));
            if (type == double.class || type == Double.class) return Optional.of(Double.parseDouble(t));
            if (type == float.class || type == Float.class) return Optional.of(Float.parseFloat(t));
            if (type == short.class || type == Short.class) return Optional.of(Short.parseShort(t));
            if (type == byte.class || type == Byte.class) return Optional.of(Byte.parseByte(t));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true".equals(t) || "false".equals(t) ? Optional.of(Boolean.parseBoolean(t)) : Optional.empty();
        }
        if (type == char.class || type == Character.class) {
            return t.length() == 1 ? Optional.of(t.charAt(0)) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * A constant of an enum no plugin declares, which the grammar does not read: {@code ChronoUnit.DAYS} or
     * {@code java.time.temporal.ChronoUnit.DAYS}. Nothing else — a variable or a call stays unreadable.
     */
    private static Optional<Object> constant(Class<?> enumType, String written) {
        int dot = written.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        String qualifier = written.substring(0, dot).replace(" ", "");
        String name = written.substring(dot + 1).trim();
        String canonical = enumType.getCanonicalName();
        if (!qualifier.equals(enumType.getSimpleName()) && !qualifier.equals(canonical)) return Optional.empty();
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return Optional.of(constant);
        }
        return Optional.empty();
    }

    private static Kept kept(ValueGrammar grammar, Type leaf, String written) {
        return new Kept(written, grammar.freshInitializer(leaf));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<Parts> parts(ComponentType<?> component, Object value) {
        try {
            List<Class<?>> types = component.componentTypes();
            List<Object> values = ((ComponentType) component).components(value);
            if (types == null || values == null || types.size() != values.size()) return Optional.empty();
            return Optional.of(new Parts(component, List.copyOf(types), new ArrayList<>(values),
                    labels(component, types)));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** The factory's parameter names when the class was compiled with them, else each part's simple type name. */
    private static List<String> labels(ComponentType<?> component, List<Class<?>> types) {
        List<String> out = new ArrayList<>();
        Parameter[] named = new Parameter[0];
        try {
            Executable factory = component.factory();
            if (factory != null) named = factory.getParameters();
        } catch (RuntimeException | LinkageError e) {
            // labels are a courtesy; the type names below still say what each row is
        }
        boolean useNames = named.length == types.size() && Arrays.stream(named).allMatch(Parameter::isNamePresent);
        for (int i = 0; i < types.size(); i++) out.add(useNames ? named[i].getName() : types.get(i).getSimpleName());
        return out;
    }
}
