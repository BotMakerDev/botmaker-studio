package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
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
 * code that throws declines that value, and a chain on part 0 is read and never written.
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
        MethodInvocation call = factoryCall(container.get().factory(), names);
        for (int i = 0; i < parts.size(); i++) {
            Optional<Expression> part = type(partTypes.get(i), parts.get(i), names);
            if (part.isEmpty()) return Optional.empty();
            arguments(call).add(part.get());
        }
        return Optional.of(call);
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
            MethodInvocation call = factoryCall(container.factory(), names);
            for (Object part : container.partsOf(value)) {
                Optional<Expression> written = any(part, names);
                if (written.isEmpty()) return Optional.empty();
                arguments(call).add(written.get());
            }
            return Optional.of(call);
        }
        ComponentType<?> exact = grammar.canonical(value.getClass());
        if (exact != null) return call(exact, value, names);
        for (ComponentType<?> component : grammar.components()) {
            Class<?> type = ValueGrammar.classOf(component);
            if (type != null && type.isInstance(value)) return call(component, value, names);
        }
        return Optional.empty();
    }

    private Optional<Expression> call(ComponentType<?> component, Object value, Names names) {
        Class<?> type = ValueGrammar.classOf(component);
        if (type == null || !type.isInstance(value)) return Optional.empty();
        Optional<Factory> factory = Factory.of(component);
        if (factory.isEmpty() || factory.get().kind() == Factory.Kind.RECEIVER) return Optional.empty();
        List<Object> parts;
        try {
            parts = component.componentsOf(value);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        if (parts == null) return Optional.empty();
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
        MethodInvocation call = factoryCall(container.get().factory(), names);
        for (JavaValue part : parts) {
            arguments(call).add(part.copyInto(names.ast));
            names.addAll(part.imports());
        }
        return Optional.of(names.value(call));
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
