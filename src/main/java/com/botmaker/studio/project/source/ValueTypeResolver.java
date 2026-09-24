package com.botmaker.studio.project.source;

import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.SourceNames;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place a type <em>written</em> in a bot's source becomes the {@link java.lang.reflect.Type} of a
 * value ({@link ValueTypes}).
 *
 * <p><b>Binding first.</b> A unit parsed against the project's classpath ({@link BotParser}) says which class
 * a type is, type arguments and all; its canonical name is then looked up <em>exactly</em>
 * ({@link ValueGrammar#named}). Without a binding — no classpath, a pom that does not resolve, a type the
 * classpath lacks — the written name is resolved through the unit's imports ({@link SourceNames}), tried
 * against the same exact names. What replaced this (2026-09-24) resolved by suffix: {@code Duration} was
 * whichever declared type's name ended in {@code Duration}.
 *
 * <p><b>Recursive, and unbounded.</b> {@code Map<String, List<Duration>>} reads as what it is. An array, a
 * wildcard, a type variable and a container whose arity disagrees with its own read as
 * {@link ValueTypes.Unknown}: shown, never rewritten.
 *
 * <p><b>A plugin's type wins a name clash</b> with a class the bot declares, when there is no binding to
 * settle it — the reading every earlier release gave.
 */
public final class ValueTypeResolver {

    private ValueTypeResolver() {}

    /**
     * The type {@code type} means as a value's type.
     *
     * @param botClasses the canonical names of the classes the bot declares itself
     */
    public static java.lang.reflect.Type of(ValueGrammar grammar, Type type, int extraDimensions,
                                            Set<String> botClasses) {
        if (type == null) return ValueTypes.NONE;
        String written = type.toString().strip();
        if (extraDimensions > 0 || type.isArrayType() || type.isWildcardType() || type.isIntersectionType()
                || type.isUnionType()) {
            return new ValueTypes.Unknown(written);
        }
        if (type instanceof PrimitiveType primitive) {
            return grammar.named(primitive.getPrimitiveTypeCode().toString())
                    .<java.lang.reflect.Type>map(cls -> cls).orElse(new ValueTypes.Unknown(written));
        }
        if (type instanceof ParameterizedType parameterized) {
            List<java.lang.reflect.Type> arguments = new ArrayList<>();
            for (Object argument : parameterized.typeArguments()) {
                arguments.add(of(grammar, (Type) argument, 0, botClasses));
            }
            Optional<String> raw = canonical(parameterized.getType(), grammar, botClasses, true);
            if (raw.isPresent()) {
                for (ValueContainer<?> container : ValueContainer.ALL) {
                    if (JavaNames.canonical(container.type()).equals(raw.get())) {
                        return container.arity() == arguments.size()
                                ? ValueTypes.of(container, arguments) : new ValueTypes.Unknown(written);
                    }
                }
                if (botClasses.contains(raw.get())) return new ValueTypes.BotClass(raw.get(), arguments);
            }
            return new ValueTypes.Unknown(written);
        }
        Optional<String> canonical = canonical(type, grammar, botClasses, false);
        if (canonical.isEmpty()) return new ValueTypes.Unknown(written);
        Optional<Class<?>> known = grammar.named(canonical.get());
        if (known.isPresent()) return known.get();
        if (botClasses.contains(canonical.get())) return new ValueTypes.BotClass(canonical.get(), List.of());
        return new ValueTypes.Unknown(written);
    }

    /**
     * The canonical name a written type means, from its binding or, without one, from the names the grammar
     * and the bot declare — or empty.
     */
    private static Optional<String> canonical(Type type, ValueGrammar grammar, Set<String> botClasses,
                                              boolean container) {
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && !binding.isRecovered()) return Optional.of(binding.getErasure().getQualifiedName());
        if (!(type instanceof SimpleType simple)) return Optional.empty();
        Name name = simple.getName();
        List<String> candidates = new ArrayList<>();
        if (container) for (ValueContainer<?> each : ValueContainer.ALL) candidates.add(JavaNames.canonical(each.type()));
        candidates.addAll(grammar.names());
        candidates.addAll(botClasses);
        for (String candidate : candidates) {
            if (SourceNames.refersTo(name, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }
}
