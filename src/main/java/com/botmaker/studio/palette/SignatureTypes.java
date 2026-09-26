package com.botmaker.studio.palette;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/**
 * Between a {@link SignatureType} — what a function's signature carries — and a {@link Type}, which is what
 * the type chooser picks (2026-09-26).
 *
 * <p>A pick becomes a {@link SignatureType.Described} whenever a {@link BotType.Choice} names it, so every
 * rule written against the catalogue (its default, a migration's list import) still applies; anything else
 * is a {@link SignatureType.Typed}.
 */
public final class SignatureTypes {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "char", char.class, "byte", byte.class, "short", short.class,
            "int", int.class, "long", long.class, "float", float.class, "double", double.class,
            "void", void.class, "String", String.class);

    private SignatureTypes() {}

    /** The signature type for a type the chooser picked. */
    public static SignatureType of(Type type) {
        return choiceFor(type).<SignatureType>map(SignatureType::of).orElseGet(() -> new SignatureType.Typed(type));
    }

    /**
     * What the chooser opens on for {@code signature}: the choice's type, or the carried text as an unknown
     * spelling the button shows as written.
     */
    public static Type typeOf(SignatureType signature) {
        return switch (signature) {
            case SignatureType.Typed typed -> typed.type();
            case SignatureType.Described described -> {
                BotType.Choice choice = described.choice();
                Optional<Class<?>> element = classNamed(choice.type().typeName());
                if (element.isEmpty()) yield new ValueTypes.Unknown(choice.sourceName());
                yield choice.isList() ? ValueTypes.listOf(element.get()) : element.get();
            }
            case SignatureType.Kept kept -> new ValueTypes.Unknown(kept.sourceName());
        };
    }

    /** The choice naming {@code type} exactly — a class the catalogue offers, alone or in one list. */
    static Optional<BotType.Choice> choiceFor(Type type) {
        if (type instanceof Class<?> cls) return exact(cls).map(BotType.Choice::of);
        if (type instanceof ValueTypes.Parameterized p && p.raw() == java.util.List.class
                && p.last() instanceof Class<?> element) {
            return exact(element).filter(BotType::listable).map(BotType.Choice::listOf);
        }
        return Optional.empty();
    }

    /** The offered type that is {@code cls} itself, compared by canonical name, never by simple name alone. */
    private static Optional<BotType> exact(Class<?> cls) {
        String canonical = JavaNames.canonical(cls);
        for (BotType offered : BotType.values()) {
            String name = offered.typeName();
            Optional<Class<?>> named = classNamed(name);
            if (named.isPresent() ? named.get() == cls : name.equals(canonical)) return Optional.of(offered);
        }
        return Optional.empty();
    }

    /** A primitive, {@code String}, or a class the bound plugins' grammar can name. */
    private static Optional<Class<?>> classNamed(String name) {
        if (name == null) return Optional.empty();
        Class<?> known = PRIMITIVES.get(name);
        if (known != null) return Optional.of(known);
        return PluginHost.grammar().named(name);
    }

    /** The fresh value a {@code type} is seeded with, as the grammar writes it — empty when it has none. */
    public static Optional<JavaValue> freshValue(Type type) {
        ValueGrammar grammar = PluginHost.grammar();
        return grammar.freshSpelling(type);
    }

    /** {@link SignatureType#defaultText()} for a {@link SignatureType.Typed}: what the write path seeds. */
    static String defaultText(Type type) {
        Optional<JavaValue> fresh = freshValue(type);
        if (fresh.isPresent()) return fresh.get().source();
        if (type instanceof Class<?> cls && cls.isPrimitive()) {
            return switch (cls.getName()) {
                case "boolean" -> "false";
                case "char" -> "'a'";
                case "double", "float" -> "0.0";
                case "void" -> "";
                default -> "0";
            };
        }
        return "null";
    }
}
