package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.api.value.Wither;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The write half of {@link ValueGrammar}: a value as a JDT expression <em>tree</em>, built node by node.
 *
 * <p><b>No Java is concatenated here (2026-09-24).</b> A constructor is {@code ast.newClassInstanceCreation()},
 * a factory call {@code ast.newMethodInvocation()}, a constant {@code ast.newQualifiedName(…)}, a literal
 * {@link JdkLiterals#node}. What leaves is a {@link JavaValue}, whose node a sink copies into its own file's
 * tree; before this, the same shape left as a string that every sink placed into its rewrite verbatim.
 *
 * <p>Every rule of the old writer stands: one part that cannot be written empties the whole answer, plugin
 * code that throws declines that value, and a chain on part 0 is written only for a value the declaration's
 * own factory would lose part of (2026-09-27, {@link #call}). A declaration's own withers are links after its
 * factory, each written only when its part differs from what the factory makes (2026-10-09, {@link #linked}).
 */
final class ValueWriter {

    private final ValueGrammar grammar;

    ValueWriter(ValueGrammar grammar) {
        this.grammar = grammar;
    }

    /** How one write names a class: fully qualified, or by its simple name with the import collected. */
    static final class Names {

        final AST ast = AST.newAST(AST.getJLSLatest(), false);
        private final boolean qualified;
        private final Set<String> imports = new LinkedHashSet<>();

        Names(boolean qualified) {
            this.qualified = qualified;
        }

        Name of(Class<?> type) {
            if (qualified && !JavaNames.importName(type).isEmpty()) return ast.newName(JavaNames.canonical(type));
            return ValueTypes.name(ast, type, imports);
        }

        void addAll(List<String> more) {
            imports.addAll(more);
        }

        JavaValue value(Expression node) {
            return JavaValue.built(node, imports);
        }
    }

    // ---- a value of a known type -------------------------------------------------------------------------

    Optional<Expression> type(Type type, Object value, Names names) {
        if (type instanceof Class<?> cls) return ofClass(cls, value, names);
        Optional<ValueContainer<?>> container = ValueTypes.container(type);
        // A class the bot declares is BotRecords' to write; an unknown type is never written.
        if (container.isEmpty()) return Optional.empty();
        if (value == null || !container.get().type().isInstance(value)) return Optional.empty();
        List<Object> parts = container.get().partsOf(value);
        List<Type> partTypes = container.get().partTypes(ValueTypes.arguments(type), parts.size());
        if (partTypes.size() != parts.size()) return Optional.empty();
        ContainerCall call = containerCall(container.get().factory(), names);
        for (int i = 0; i < parts.size(); i++) {
            Optional<Expression> part = type(partTypes.get(i), parts.get(i), names);
            if (part.isEmpty()) return Optional.empty();
            call.arguments().add(part.get());
        }
        return Optional.of(call.node());
    }

    /**
     * A value of a declared class: a JDK literal, the component that writes it, an enum constant, or — for a
     * type declared with no component — the value by its own runtime class, and a {@code String} as the
     * source it is written as.
     */
    Optional<Expression> ofClass(Class<?> type, Object value, Names names) {
        if (value == null) return Optional.empty();
        if (type == null) return any(value, names);
        if (JdkLiterals.handles(type)) return JdkLiterals.node(names.ast, type, value);
        ComponentType<?> component = grammar.canonical(type);
        if (component != null) return call(component, value, names);
        if (type.isEnum()) return constant(type, value, names);
        if (isHostContainer(type)) return any(value, names);
        if (value instanceof String source) {
            // A type declared with no component crosses as the Java it is written as: the SDK's CaptureSource.
            return SourceNode.parse(source).map(node -> JavaValue.kept(node).copyInto(names.ast));
        }
        return type.isInstance(value) ? any(value, names) : Optional.empty();
    }

    /** {@code value} by its own runtime class — the untyped counterpart of reading by spelling. */
    Optional<Expression> any(Object value, Names names) {
        if (value == null) return Optional.empty();
        Optional<Expression> literal = JdkLiterals.nodeAny(names.ast, value);
        if (literal.isPresent()) return literal;
        if (value instanceof Enum<?> constant) return constant(constant.getDeclaringClass(), value, names);
        for (ValueContainer<?> container : ValueContainer.ALL) {
            if (!container.type().isInstance(value)) continue;
            ContainerCall call = containerCall(container.factory(), names);
            for (Object part : container.partsOf(value)) {
                Optional<Expression> written = any(part, names);
                if (written.isEmpty()) return Optional.empty();
                call.arguments().add(written.get());
            }
            return Optional.of(call.node());
        }
        ComponentType<?> exact = grammar.canonical(value.getClass());
        if (exact != null) return call(exact, value, names);
        for (ComponentType<?> component : grammar.components()) {
            Class<?> type = ValueGrammar.classOf(component);
            if (type != null && type.isInstance(value)) return call(component, value, names);
        }
        return Optional.empty();
    }

    /**
     * {@code value} through {@code component}, or — when that would lose part of it — through a chain on part 0
     * that keeps it: {@code Combo.of(Key.CTRL, Key.S).held(Duration.ofMillis(200))}, where the declaration's
     * {@code Combo.of} has no hold. Before 2026-09-27 a chain was never written and such a value was written
     * with the part dropped. With no chain that keeps it, the factory writes it as before: a class whose
     * {@code equals} is identity never round-trips equal, and must not stop being written for that.
     */
    private Optional<Expression> call(ComponentType<?> component, Object value, Names names) {
        Class<?> type = ValueGrammar.classOf(component);
        if (type == null || !type.isInstance(value)) return Optional.empty();
        List<ComponentType<?>> chains = grammar.chains(type);
        if (!chains.isEmpty() && !keeps(component, value)) {
            for (ComponentType<?> chain : chains) {
                Optional<Expression> written = chained(component, chain, type, value, names);
                if (written.isPresent()) return written;
            }
        }
        return linked(component, type, value, names);
    }

    /**
     * {@code value} as one of its type's named constants, else its factory's call followed by a link for each
     * wither whose part differs from what the factory alone makes: {@code Flow.activity(COLLECT, Collect::body)
     * .described("Picks up ore").goesHome()} (2026-10-09). A part as the factory makes it is left out, so a
     * value with nothing set is the bare call. The chain always starts at the factory's call, never at a
     * constant, which is the only start the reader unwinds to. A call declaring no wither is its factory alone.
     * A link that cannot be written — a flag the factory turns on and the value has off, an argument of a type
     * nothing writes — is left out and only its part lost, as a lossy factory always lost one.
     */
    private Optional<Expression> linked(ComponentType<?> component, Class<?> type, Object value, Names names) {
        List<? extends Wither<?>> withers = ValueGrammar.withersOf(component);
        if (withers.isEmpty()) return direct(component, type, value, names);
        Optional<Expression> named = namedConstant(component, type, value, names);
        if (named.isPresent()) return named;
        List<Object> parts;
        List<Object> madeParts;
        int fixed;
        try {
            parts = component.componentsOf(value);
            fixed = parts == null ? -1 : parts.size() - withers.size();
            if (fixed < 0) return Optional.empty();
            Object made = component.build(new ArrayList<>(parts.subList(0, fixed)));
            madeParts = made == null ? null : component.componentsOf(made);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        if (madeParts == null || madeParts.size() != parts.size()) return Optional.empty();
        Optional<Expression> on = factoryWritten(component, type, value, names);
        if (on.isEmpty()) return Optional.empty();
        Expression chain = on.get();
        for (int i = 0; i < withers.size(); i++) {
            Object part = parts.get(fixed + i);
            if (Objects.equals(part, madeParts.get(fixed + i))) continue;
            Wither<?> wither = withers.get(i);
            Optional<Expression> argument = Optional.empty();
            if (wither.flag()) {
                if (!Boolean.TRUE.equals(part)) continue;
            } else {
                argument = ofClass(wither.partType(), part, names);
                if (argument.isEmpty()) continue;
            }
            MethodInvocation link = names.ast.newMethodInvocation();
            link.setExpression(chain);
            link.setName(names.ast.newSimpleName(wither.method().getName()));
            argument.ifPresent(arguments(link)::add);
            chain = link;
        }
        return Optional.of(chain);
    }

    /** Whether {@code component}'s own parts build {@code value} back whole; plugin code that throws does not. */
    private static boolean keeps(ComponentType<?> component, Object value) {
        try {
            List<Object> parts = component.componentsOf(value);
            return parts != null && value.equals(component.build(parts));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * {@code receiver.method(…)} for {@code chain}, whose part 0 is written by {@code component} alone — so a
     * chain is one link deep and a chain that answers the value itself as its receiver (a wither) writes
     * nothing.
     */
    private Optional<Expression> chained(ComponentType<?> component, ComponentType<?> chain, Class<?> type,
                                         Object value, Names names) {
        Optional<Factory> factory = Factory.of(chain);
        if (factory.isEmpty() || factory.get().kind() != Factory.Kind.RECEIVER) return Optional.empty();
        List<Object> parts;
        try {
            parts = chain.componentsOf(value);
            if (parts == null || parts.isEmpty() || !value.equals(chain.build(parts))) return Optional.empty();
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        Object receiver = parts.getFirst();
        if (!type.isInstance(receiver) || receiver.equals(value) || !keeps(component, receiver)) {
            return Optional.empty();
        }
        // The receiver with its own links: the factory's parts alone would drop what its withers set.
        Optional<Expression> on = linked(component, type, receiver, names);
        if (on.isEmpty()) return Optional.empty();
        MethodInvocation invocation = names.ast.newMethodInvocation();
        invocation.setExpression(on.get());
        invocation.setName(names.ast.newSimpleName(factory.get().name()));
        List<Class<?>> declared = ValueGrammar.componentTypesOf(chain);
        List<Expression> arguments = arguments(invocation);
        for (int i = 1; i < parts.size(); i++) {
            Optional<Expression> part = ofClass(ValueGrammar.partType(declared, i, parts.size()), parts.get(i), names);
            if (part.isEmpty()) return Optional.empty();
            arguments.add(part.get());
        }
        return Optional.of(invocation);
    }

    /** {@code value} as one of its type's named constants, else through {@code component}'s own factory. */
    private Optional<Expression> direct(ComponentType<?> component, Class<?> type, Object value, Names names) {
        Optional<Expression> named = namedConstant(component, type, value, names);
        return named.isPresent() ? named : factoryWritten(component, type, value, names);
    }

    /** {@code component}'s own factory over {@code value}'s factory parts — a wither's are {@link #linked}'s. */
    private Optional<Expression> factoryWritten(ComponentType<?> component, Class<?> type, Object value,
                                                Names names) {
        Optional<Factory> factory = Factory.of(component);
        if (factory.isEmpty() || factory.get().kind() == Factory.Kind.RECEIVER) return Optional.empty();
        List<Object> parts;
        try {
            parts = component.componentsOf(value);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        if (parts == null) return Optional.empty();
        // The factory's own parts: a wither's follow them, and are written as links (linked).
        int fixed = parts.size() - ValueGrammar.withersOf(component).size();
        if (fixed < 0) return Optional.empty();
        parts = parts.subList(0, fixed);
        List<Class<?>> declared = ValueGrammar.componentTypesOf(component);
        List<Expression> arguments;
        if (factory.get().kind() == Factory.Kind.CONSTRUCTOR) {
            ClassInstanceCreation creation = names.ast.newClassInstanceCreation();
            creation.setType(names.ast.newSimpleType(names.of(type)));
            @SuppressWarnings("unchecked")
            List<Expression> list = creation.arguments();
            arguments = list;
            if (!fill(arguments, declared, parts, names)) return Optional.empty();
            return Optional.of(creation);
        }
        MethodInvocation invocation = factoryCall(factory.get(), names);
        arguments = arguments(invocation);
        if (!fill(arguments, declared, parts, names)) return Optional.empty();
        return Optional.of(invocation);
    }

    /**
     * {@code value} as one of the constants its type names ({@code ComponentType.constants}) — {@code
     * ZoneOffset.UTC} — or empty. Only a public static final field of {@code type} itself counts: the reader
     * reads no other, so a written name must be one it reads back.
     */
    private static Optional<Expression> namedConstant(ComponentType<?> component, Class<?> type, Object value,
                                                      Names names) {
        List<java.lang.reflect.Field> constants;
        try {
            constants = component.constants();
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        if (constants == null) return Optional.empty();
        for (java.lang.reflect.Field field : constants) {
            if (field == null || !field.getDeclaringClass().equals(type)) continue;
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                if (value.equals(field.get(null))) {
                    return Optional.of(names.ast.newQualifiedName(names.of(type),
                            names.ast.newSimpleName(field.getName())));
                }
            } catch (IllegalAccessException | RuntimeException | LinkageError e) {
                // A field that cannot be read names nothing.
            }
        }
        return Optional.empty();
    }

    private boolean fill(List<Expression> into, List<Class<?>> declared, List<Object> parts, Names names) {
        for (int i = 0; i < parts.size(); i++) {
            Optional<Expression> part = ofClass(ValueGrammar.partType(declared, i, parts.size()), parts.get(i), names);
            if (part.isEmpty()) return false;
            into.add(part.get());
        }
        return true;
    }

    private static Optional<Expression> constant(Class<?> type, Object value, Names names) {
        return value instanceof Enum<?> constant && type.isInstance(value)
                ? Optional.of(names.ast.newQualifiedName(names.of(type), names.ast.newSimpleName(constant.name())))
                : Optional.empty();
    }

    // ---- a composite over parts already written ----------------------------------------------------------

    /**
     * The call {@code type}'s container is written as, over parts that are already values — each copied into
     * the new tree, its imports carried. Empty when the container cannot hold that many parts.
     */
    Optional<JavaValue> compose(Type type, List<JavaValue> parts) {
        Optional<ValueContainer<?>> container = ValueTypes.container(type);
        // Not parts.contains(null): an immutable list throws on the question.
        if (container.isEmpty() || parts == null || parts.stream().anyMatch(Objects::isNull)) return Optional.empty();
        if (container.get().partTypes(ValueTypes.arguments(type), parts.size()).size() != parts.size()) {
            return Optional.empty();
        }
        Names names = new Names(false);
        ContainerCall call = containerCall(container.get().factory(), names);
        for (JavaValue part : parts) {
            call.arguments().add(part.copyInto(names.ast));
            names.addAll(part.imports());
        }
        return Optional.of(names.value(call.node()));
    }

    // ---- a fresh value -----------------------------------------------------------------------------------

    /**
     * A freshly declared field's value: the declaration's {@code fresh()}, or a call to its
     * {@code freshCall()}; a container starts empty. A type nothing declares, and a class the bot declares,
     * get nothing.
     */
    Optional<Expression> fresh(Type type, Names names) {
        if (type instanceof Class<?> cls) {
            Optional<PluginType<?>> declared = grammar.type(cls);
            if (declared.isEmpty()) return Optional.empty();
            Object fresh;
            Method freshCall;
            try {
                fresh = declared.get().fresh();
                freshCall = declared.get().freshCall();
            } catch (RuntimeException | LinkageError e) {
                return Optional.empty();
            }
            if (fresh != null) {
                Optional<Expression> written = type(type, fresh, names);
                if (written.isPresent()) return written;
            }
            return freshCall(freshCall, declared.get(), names);
        }
        Optional<ValueContainer<?>> container = ValueTypes.container(type);
        return container.isPresent() ? type(type, container.get().build(List.of()), names) : Optional.empty();
    }

    /**
     * {@code Owner.method()} for a type's {@code freshCall()}, or empty for none or one of the wrong shape —
     * checked again here rather than trusted, since a wrong one would be written into somebody's file.
     */
    private static Optional<Expression> freshCall(Method method, PluginType<?> type, Names names) {
        if (method == null || !Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                || method.getParameterCount() != 0 || type.type() == null
                || !method.getReturnType().getName().equals(type.type().getName())) {
            return Optional.empty();
        }
        MethodInvocation call = names.ast.newMethodInvocation();
        call.setExpression(names.of(method.getDeclaringClass()));
        call.setName(names.ast.newSimpleName(method.getName()));
        return Optional.of(call);
    }

    // ---- plumbing ----------------------------------------------------------------------------------------

    /** A container's call and the argument list its parts go into. */
    private record ContainerCall(Expression node, List<Expression> arguments) {
    }

    /**
     * {@code Owner.factory()} for a container, or {@code new Owner<>()} for the one written with a constructor
     * ({@code Deque}) — the diamond, since the target type gives the element type and spelling it again would
     * be one more thing to keep in step with the declaration.
     */
    @SuppressWarnings("unchecked")
    private static ContainerCall containerCall(Factory factory, Names names) {
        if (factory.kind() == Factory.Kind.CONSTRUCTOR) {
            ClassInstanceCreation creation = names.ast.newClassInstanceCreation();
            creation.setType(names.ast.newParameterizedType(names.ast.newSimpleType(names.of(factory.owner()))));
            return new ContainerCall(creation, creation.arguments());
        }
        MethodInvocation call = factoryCall(factory, names);
        return new ContainerCall(call, arguments(call));
    }

    private static MethodInvocation factoryCall(Factory factory, Names names) {
        MethodInvocation call = names.ast.newMethodInvocation();
        call.setExpression(names.of(factory.owner()));
        call.setName(names.ast.newSimpleName(factory.name()));
        return call;
    }

    @SuppressWarnings("unchecked")
    private static List<Expression> arguments(MethodInvocation call) {
        return call.arguments();
    }

    private static boolean isHostContainer(Class<?> type) {
        for (ValueContainer<?> container : ValueContainer.ALL) if (container.type() == type) return true;
        return false;
    }
}
