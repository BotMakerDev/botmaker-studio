package com.botmaker.studio.palette;

import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueTypes;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * A type as a person reads it: Java's own spelling with every class by its simple name —
 * {@code List<Map<String, Point>>}, {@code int}. What the type chooser's button and a signature preview show.
 */
public final class TypeNames {

    private TypeNames() {}

    public static String label(Type type) {
        return switch (type) {
            case null -> "";
            case ValueTypes.Parameterized p -> JavaNames.simple(p.raw()) + arguments(p.arguments());
            case ValueTypes.BotClass bot -> simple(bot.qualifiedName()) + arguments(bot.arguments());
            default -> ValueTypes.sourceName(type);
        };
    }

    /**
     * A name for a variable of {@code type}: its lower-cased simple name, {@code points} for a list — and for a
     * primitive, whose lower-cased name is the keyword, {@code DefaultNames}' word ({@code number}).
     */
    public static String variableName(Type type) {
        if (type instanceof ValueTypes.Parameterized p) return variableName(p.last()) + "s";
        String simple = label(type);
        if (simple.isEmpty()) return "value";
        String lowered = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        return javax.lang.model.SourceVersion.isName(lowered)
                ? lowered : com.botmaker.studio.util.DefaultNames.forType(simple);
    }

    private static String arguments(List<Type> arguments) {
        if (arguments.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Type argument : arguments) parts.add(label(boxed(argument)));
        return "<" + String.join(", ", parts) + ">";
    }

    private static String simple(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    /** A type argument is never primitive: {@code int} inside angle brackets is {@code Integer}. */
    public static Type boxed(Type t) {
        return t instanceof Class<?> cls && cls.isPrimitive() && cls != void.class
                ? java.lang.invoke.MethodType.methodType(cls).wrap().returnType() : t;
    }
}
