package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Name;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The type of a value, in the JDK's own vocabulary: {@link java.lang.reflect.Type}.
 *
 * <p><b>What this replaced (2026-09-24)</b> is {@code ValueForm}, a sealed tree of the host's own whose leaf
 * held a type's <em>name</em> as a string, resolved by suffix against whatever the plugins declared. Every
 * question asked of it went back through that string. A value's type is now what Java already calls one:
 *
 * <ul>
 *   <li>a {@link Class} — a JDK literal type or a class a plugin declares, the plugin's own {@code Class}
 *       object, so its identity is its name and nothing is looked up again;</li>
 *   <li>a {@link Parameterized} over a host container — {@code List<Duration>},
 *       {@code Map<String, List<Point>>} — which is a real {@link ParameterizedType};</li>
 *   <li>a {@link BotClass} — a class the bot itself declares, which the host cannot load, known by its
 *       qualified name from the binding (or the imports);</li>
 *   <li>and {@link Unknown} — anything else, kept with its spelling so it can be shown, never written.</li>
 * </ul>
 *
 * <p><b>Resolved once.</b> The only place a written type becomes one of these is
 * {@code project/source/ValueTypeResolver}, from a JDT binding or, without one, the unit's imports. The
 * picker builds them from classes it was handed. Nothing downstream turns a name back into a type.
 */
public final class ValueTypes {

    private ValueTypes() {}

    /** The type of a value nothing declared and nothing wrote: no spelling, no class. */
    public static final Unknown NONE = new Unknown("");

    /**
     * A host container over type arguments. A real {@link ParameterizedType}, so anything that reads the JDK's
     * vocabulary reads it.
     */
    public record Parameterized(Class<?> raw, List<Type> arguments) implements ParameterizedType {

        public Parameterized {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.toArray(Type[]::new);
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public String getTypeName() {
            return sourceName(this);
        }

        /** The element of a {@code List}, or the value of a {@code Map} — the last argument either way. */
        public Type last() {
            return arguments.isEmpty() ? NONE : arguments.getLast();
        }
    }

    /**
     * A class the bot declares itself, by its qualified name. Not a {@link Class}: a second project's
     * {@code Point} is a different class, and the host never loads a bot's code.
     *
     * @param arguments empty for a non-generic class, which is legal and common
     */
    public record BotClass(String qualifiedName, List<Type> arguments) implements Type {

        public BotClass {
            qualifiedName = qualifiedName == null ? "" : qualifiedName;
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        @Override
        public String getTypeName() {
            return sourceName(this);
        }
    }

    /**
     * A type nothing here can name — an array, a wildcard, a class no plugin declares — kept as it was written
     * so a read-only cell can say what it is.
     */
    public record Unknown(String written) implements Type {

        public Unknown {
            written = written == null ? "" : written.strip();
        }

        @Override
        public String getTypeName() {
            return written;
        }
    }

    // ---- building -----------------------------------------------------------------------------------------

    /**
     * {@code container} over {@code arguments}, padded with {@link #NONE} or truncated to its arity, so a caller
     * handing the wrong number still gets a usable type rather than an exception.
     */
    public static Parameterized of(ValueContainer<?> container, List<? extends Type> arguments) {
        List<Type> given = arguments == null ? List.of() : new ArrayList<>(arguments);
        Type[] filled = new Type[container.arity()];
        for (int i = 0; i < filled.length; i++) {
            Type argument = i < given.size() ? given.get(i) : null;
            filled[i] = argument == null ? NONE : argument;
        }
        return new Parameterized(container.type(), Arrays.asList(filled));
    }

    /** A list of {@code element}. */
    public static Parameterized listOf(Type element) {
        return of(ValueContainer.LIST, List.of(element));
    }

    /** A map from {@code key} to {@code value}. */
    public static Parameterized mapOf(Type key, Type value) {
        return of(ValueContainer.MAP, List.of(key, value));
    }

    // ---- asking -------------------------------------------------------------------------------------------

    /** The host container {@code type} is, or empty — {@code List<…>}, {@code Map<…>}, {@code Map.Entry<…>}. */
    public static Optional<ValueContainer<?>> container(Type type) {
        if (!(type instanceof Parameterized parameterized)) return Optional.empty();
        for (ValueContainer<?> candidate : ValueContainer.ALL) {
            if (candidate.type() == parameterized.raw()) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** {@code type}'s arguments: a container's or a bot class's, and none for anything else. */
    public static List<Type> arguments(Type type) {
        return switch (type) {
            case Parameterized parameterized -> parameterized.arguments();
            case BotClass bot -> bot.arguments();
            case null, default -> List.of();
        };
    }

    /**
     * The single type a user types values of: the type itself for a leaf, the element of a list or the value
     * of a map, and {@code null} for a container of containers or a bot's class. What a declared set of
     * choices and a declared range are asked of.
     */
    public static Type leaf(Type type) {
        return switch (type) {
            case Class<?> cls -> cls;
            case Unknown unknown -> unknown;
            case Parameterized parameterized when isLeaf(parameterized.last()) -> parameterized.last();
            case null, default -> null;
        };
    }

    /** Whether {@code type} is a leaf: a class or an unknown spelling. */
    public static boolean isLeaf(Type type) {
        return type instanceof Class<?> || type instanceof Unknown;
    }

    /**
     * How deep the containers go: {@code 0} for a leaf, {@code 1} for {@code List<Duration>}, {@code 2} for
     * {@code Map<String, List<Point>>}. What a picker compares its cap against.
     */
    public static int depth(Type type) {
        return switch (type) {
            case Parameterized parameterized ->
                    1 + parameterized.arguments().stream().mapToInt(ValueTypes::depth).max().orElse(0);
            case BotClass bot -> bot.arguments().stream().mapToInt(ValueTypes::depth).max().orElse(0);
            case null, default -> 0;
        };
    }

    /**
     * How a declaration writes {@code type} — {@code Duration}, {@code java.util.Map<String, Point>}.
     *
     * <p>A class is spelled by its simple name, and its import is {@link #imports}' answer. A host container is
     * written fully qualified, so a caller composing a declaration never needs an import for it. Primitives are
     * boxed inside angle brackets and only there.
     */
    public static String sourceName(Type type) {
        return switch (type) {
            case Class<?> cls -> JavaNames.simple(cls);
            case Unknown unknown -> unknown.written();
            case Parameterized parameterized -> JavaNames.canonical(parameterized.raw())
                    + typeArguments(parameterized.arguments());
            case BotClass bot -> bot.qualifiedName() + typeArguments(bot.arguments());
            case null -> "";
            default -> type.getTypeName();
        };
    }

    /**
     * {@code type} as a node of {@code ast} — {@code Map<String, List<Duration>>} — naming every class by its
     * simple name and adding what that needs to {@code imports}. Empty for a type with an unknown leaf
     * anywhere, which nothing writes.
     */
    public static Optional<org.eclipse.jdt.core.dom.Type> node(AST ast, Type type, Set<String> imports) {
        return switch (type) {
            case Class<?> cls when cls.isPrimitive() -> Optional.of(ast.newPrimitiveType(
                    org.eclipse.jdt.core.dom.PrimitiveType.toCode(cls.getName())));
            case Class<?> cls -> Optional.of(ast.newSimpleType(name(ast, cls, imports)));
            case Parameterized parameterized -> generic(ast,
                    ast.newSimpleType(name(ast, parameterized.raw(), imports)), parameterized.arguments(), imports);
            case BotClass bot -> generic(ast, ast.newSimpleType(ast.newName(bot.qualifiedName())),
                    bot.arguments(), imports);
            case null, default -> Optional.empty();
        };
    }

    private static Optional<org.eclipse.jdt.core.dom.Type> generic(AST ast, org.eclipse.jdt.core.dom.Type raw,
                                                                   List<Type> arguments, Set<String> imports) {
        if (arguments.isEmpty()) return Optional.of(raw);
        org.eclipse.jdt.core.dom.ParameterizedType out = ast.newParameterizedType(raw);
        for (Type argument : arguments) {
            // A type argument is never primitive: int inside angle brackets is written Integer.
            Type boxed = argument instanceof Class<?> cls && cls.isPrimitive()
                    ? java.lang.invoke.MethodType.methodType(cls).wrap().returnType() : argument;
            Optional<org.eclipse.jdt.core.dom.Type> node = node(ast, boxed, imports);
            if (node.isEmpty()) return Optional.empty();
            @SuppressWarnings("unchecked")
            List<org.eclipse.jdt.core.dom.Type> typeArguments = out.typeArguments();
            typeArguments.add(node.get());
        }
        return Optional.of(out);
    }

    /** {@code cls} by its simple name, its import added — nothing added for a primitive or {@code java.lang}. */
    static Name name(AST ast, Class<?> cls, Set<String> imports) {
        String importName = JavaNames.importName(cls);
        if (!importName.isEmpty()) imports.add(importName);
        return ast.newName(JavaNames.simple(cls));
    }

    /** The class a file declaring a field of {@code type} imports for each class in it, first reached first. */
    public static List<String> imports(Type type) {
        List<String> out = new ArrayList<>();
        collect(type, out);
        return List.copyOf(out);
    }

    private static void collect(Type type, List<String> out) {
        if (type instanceof Class<?> cls) {
            String name = JavaNames.importName(cls);
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        for (Type argument : arguments(type)) collect(argument, out);
    }

    private static String typeArguments(List<Type> arguments) {
        if (arguments.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<");
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) out.append(", ");
            Type argument = arguments.get(i);
            out.append(argument instanceof Class<?> cls && cls.isPrimitive()
                    ? JavaNames.simple(java.lang.invoke.MethodType.methodType(cls).wrap().returnType())
                    : sourceName(argument));
        }
        return out.append('>').toString();
    }
}
