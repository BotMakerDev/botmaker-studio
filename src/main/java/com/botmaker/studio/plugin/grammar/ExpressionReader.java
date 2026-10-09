package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.api.value.Wither;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read half of {@link ValueGrammar}, over a parsed expression rather than its text.
 *
 * <p>It switches on the node:
 *
 * <ul>
 *   <li>a literal, through {@link JdkLiterals};</li>
 *   <li>{@code new Owner(…)}, through a declared constructor;</li>
 *   <li>{@code Owner.m(…)}, through a declared static factory or a host container;</li>
 *   <li>{@code x.m(…)}, through a declared instance factory, where {@code x} is read as part 0. Chains compose
 *       because reading recurses;</li>
 *   <li>{@code factory(…).with(…).flag()}, through a declaration's withers, unwound down to its own factory;</li>
 *   <li>{@code Owner.NAME}, as an enum constant or a {@code public static final} field of a class a plugin
 *       declares.</li>
 * </ul>
 *
 * <p>Anything else is unread. The grammar's rules hold: empty declines; one unread part empties the whole
 * reading; plugin code that throws declines that value.
 */
final class ExpressionReader {

    /** One way a declared value is written: the component, and the factory it is written with. */
    record Call(ComponentType<?> component, Factory factory) {}

    private final ValueGrammar grammar;
    private final List<Call> calls;
    private final List<Class<?>> enums;
    private final List<Class<?>> declared;

    ExpressionReader(ValueGrammar grammar, List<Call> calls, List<Class<?>> enums, List<Class<?>> declared) {
        this.grammar = grammar;
        this.calls = List.copyOf(calls);
        this.enums = List.copyOf(enums);
        this.declared = List.copyOf(declared);
    }

    // ---- by form -------------------------------------------------------------------------------------------

    Optional<Object> read(Type type, SourceNode node) {
        if (type == null || node == null || node.node() == null) return Optional.empty();
        if (type instanceof Class<?> cls) return readDeclared(cls, node);
        Optional<ValueContainer<?>> container = ValueTypes.container(type);
        // A class the bot declares is BotRecords' to read; an unknown type is not read.
        if (container.isEmpty()) return Optional.empty();
        return parts(type, node).flatMap(parts -> {
            List<Object> values = new ArrayList<>(parts.size());
            for (ValueGrammar.Part part : parts) {
                Optional<Object> value = read(part.form(), part.written());
                if (value.isEmpty()) return Optional.empty();
                values.add(value.get());
            }
            return Optional.ofNullable(container.get().build(values));
        });
    }

    Optional<List<ValueGrammar.Part>> parts(Type type, SourceNode node) {
        Optional<ValueContainer<?>> container = ValueTypes.container(type);
        if (container.isEmpty() || node == null || node.node() == null) return Optional.empty();
        List<Expression> arguments = switch (unwrap(node.node())) {
            case MethodInvocation call when container.get().factory().matches(call, false) -> arguments(call);
            case ClassInstanceCreation creation when container.get().factory().matches(creation) ->
                    arguments(creation);
            case null, default -> null;
        };
        if (arguments == null) return Optional.empty();
        List<Type> types = container.get().partTypes(ValueTypes.arguments(type), arguments.size());
        if (types.size() != arguments.size()) return Optional.empty();
        List<ValueGrammar.Part> parts = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            parts.add(new ValueGrammar.Part(types.get(i), node.child(arguments.get(i))));
        }
        return Optional.of(List.copyOf(parts));
    }

    /** A value whose type is a class: a JDK literal, or one a plugin declares or a component builds. */
    private Optional<Object> readDeclared(Class<?> cls, SourceNode node) {
        if (JdkLiterals.handles(cls)) return JdkLiterals.read(cls, unwrap(node.node()));
        PluginType<?> type = grammar.declaration(cls);
        if (type == null && grammar.canonical(cls) == null) return Optional.empty();
        Optional<Object> read = value(node, cls, true);
        // A declared type nothing writes, such as an interface no call here constructs, crosses as written.
        if (read.isPresent() || type == null || cls.isEnum() || grammar.canonical(cls) != null) return read;
        return Optional.of(node.source());
    }

    /** One part of a call, read as the class its declaration gives it. */
    private Optional<Object> readPart(Class<?> type, SourceNode node) {
        if (type == null || isHostContainer(type)) return readAny(node);
        if (JdkLiterals.handles(type)) return JdkLiterals.read(type, unwrap(node.node()));
        Optional<Object> read = value(node, type, true);
        if (read.isPresent() || type.isEnum() || grammar.canonical(type) != null) return read;
        // The plugin said this part is a type nothing here writes — a flow's Collect::body — so it crosses
        // as it is written.
        return Optional.of(node.source());
    }

    // ---- by spelling ---------------------------------------------------------------------------------------

    /** The value {@code node} writes, typed by its own spelling. */
    Optional<Object> readAny(SourceNode node) {
        return node == null || node.node() == null ? Optional.empty() : value(node, null, false);
    }

    /**
     * The value {@code node} writes, when it is an {@code expected} (any class when {@code null}). A bare
     * factory or constant name, which a static import writes, is accepted only when {@code bare} and only for
     * the exact class expected: {@code of(…)} alone is every factory at once.
     */
    private Optional<Object> value(SourceNode node, Class<?> expected, boolean bare) {
        Expression expression = unwrap(node.node());
        Optional<Object> read = switch (expression) {
            case ClassInstanceCreation creation -> construct(node.child(creation), expected);
            case MethodInvocation call -> invoke(node.child(call), expected, bare);
            case QualifiedName qualified -> constant(qualified.getQualifier(),
                    qualified.getName().getIdentifier(), expected);
            case FieldAccess access when access.getExpression() instanceof Name owner ->
                    constant(owner, access.getName().getIdentifier(), expected);
            case SimpleName simple when bare && expected != null && expected.isEnum() ->
                    enumConstant(expected, simple.getIdentifier());
            default -> expected != null && JdkLiterals.handles(expected)
                    ? JdkLiterals.read(expected, expression)
                    : JdkLiterals.readAny(expression);
        };
        return read.filter(value -> expected == null || boxed(expected).isInstance(value));
    }

    private Optional<Object> construct(SourceNode node, Class<?> expected) {
        ClassInstanceCreation creation = (ClassInstanceCreation) node.node();
        for (ValueContainer<?> container : grammar.containers()) {
            if ((expected == null || expected.isAssignableFrom(container.type()))
                    && container.factory().matches(creation)) {
                return containerValue(container, arguments(creation), node);
            }
        }
        for (Call call : calls) {
            if (!assignable(expected, call) || !call.factory().matches(creation)) continue;
            Optional<Object> built = build(call, List.of(), arguments(creation), node);
            if (built.isPresent()) return built;
        }
        return Optional.empty();
    }

    private Optional<Object> invoke(SourceNode node, Class<?> expected, boolean bare) {
        MethodInvocation invocation = (MethodInvocation) node.node();
        for (ValueContainer<?> container : grammar.containers()) {
            if ((expected == null || expected.isAssignableFrom(container.type()))
                    && container.factory().matches(invocation, false)) {
                return containerValue(container, arguments(invocation), node);
            }
        }
        for (Call call : calls) {
            if (call.factory().kind() != Factory.Kind.STATIC || !assignable(expected, call)) continue;
            boolean exact = expected != null && boxed(expected).equals(ValueGrammar.classOf(call.component()));
            if (!call.factory().matches(invocation, bare && exact)) continue;
            Optional<Object> built = build(call, List.of(), arguments(invocation), node);
            if (built.isPresent()) return built;
        }
        for (Call call : calls) {
            if (call.factory().kind() != Factory.Kind.RECEIVER || !assignable(expected, call)) continue;
            if (!call.factory().matches(invocation, false)) continue;
            Optional<Object> receiver = value(node.child(invocation.getExpression()), call.factory().owner(), false);
            if (receiver.isEmpty()) continue;
            Optional<Object> built = build(call, List.of(receiver.get()), arguments(invocation), node);
            if (built.isPresent()) return built;
        }
        // After the declared chains: a wither is matched by its name alone, and a chain declaration naming the
        // same method is the more specific reading.
        for (Call call : calls) {
            if (!assignable(expected, call)) continue;
            boolean exact = expected != null && boxed(expected).equals(ValueGrammar.classOf(call.component()));
            Optional<Object> linked = linked(call, node, bare && exact);
            if (linked.isPresent()) return linked;
        }
        return Optional.empty();
    }

    /**
     * {@code factory(…).link(…).link()}: links of {@code call}'s withers, in any order and each at most once,
     * down to a call of its own factory (2026-10-09). The factory's value is taken apart, each link's part set
     * over the one the factory made, and the whole built back — so a link left out reads as the factory makes
     * it, and the same value reads the same whichever order its links were written in.
     */
    private Optional<Object> linked(Call call, SourceNode node, boolean bare) {
        List<? extends Wither<?>> withers = ValueGrammar.withersOf(call.component());
        if (withers.isEmpty()) return Optional.empty();
        Map<Integer, MethodInvocation> links = new LinkedHashMap<>();
        Expression at = unwrap(node.node());
        while (at instanceof MethodInvocation invocation && invocation.getExpression() != null) {
            int index = witherOf(withers, invocation);
            if (index < 0) break;
            // A part set twice is a chain no writer made, and which one counts is the bot's runtime's to say.
            if (links.putIfAbsent(index, invocation) != null) return Optional.empty();
            at = unwrap(invocation.getExpression());
        }
        if (links.isEmpty()) return Optional.empty();
        Optional<Object> made = own(call, node.child(at), bare);
        if (made.isEmpty()) return Optional.empty();
        List<Object> parts;
        try {
            List<Object> taken = call.component().componentsOf(made.get());
            if (taken == null) return Optional.empty();
            parts = new ArrayList<>(taken);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        int fixed = parts.size() - withers.size();
        if (fixed < 0) return Optional.empty();
        for (Map.Entry<Integer, MethodInvocation> link : links.entrySet()) {
            Wither<?> wither = withers.get(link.getKey());
            Object part = Boolean.TRUE;
            if (!wither.flag()) {
                Optional<Object> read = readPart(wither.partType(), node.child(arguments(link.getValue()).getFirst()));
                if (read.isEmpty()) return Optional.empty();
                part = read.get();
            }
            parts.set(fixed + link.getKey(), part);
        }
        try {
            return Optional.ofNullable(call.component().build(Collections.unmodifiableList(parts)));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** Which of {@code withers} {@code invocation} is a link of, by name and argument count; -1 for none. */
    private static int witherOf(List<? extends Wither<?>> withers, MethodInvocation invocation) {
        String name = invocation.getName().getIdentifier();
        int arguments = invocation.arguments().size();
        for (int i = 0; i < withers.size(); i++) {
            Wither<?> wither = withers.get(i);
            if (wither.method().getName().equals(name) && arguments == (wither.flag() ? 0 : 1)) return i;
        }
        return -1;
    }

    /** {@code node} read as a call of {@code call}'s own factory, and nothing else. */
    private Optional<Object> own(Call call, SourceNode node, boolean bare) {
        Factory factory = call.factory();
        return switch (unwrap(node.node())) {
            case ClassInstanceCreation creation when factory.matches(creation) ->
                    build(call, List.of(), arguments(creation), node);
            case MethodInvocation invocation when factory.kind() == Factory.Kind.STATIC
                                                  && factory.matches(invocation, bare) ->
                    build(call, List.of(), arguments(invocation), node);
            case MethodInvocation invocation when factory.kind() == Factory.Kind.RECEIVER
                                                  && factory.matches(invocation, false) ->
                    value(node.child(invocation.getExpression()), factory.owner(), false)
                            .flatMap(receiver -> build(call, List.of(receiver), arguments(invocation), node));
            case null, default -> Optional.empty();
        };
    }

    /** A container's parts are read by their own spelling: the untyped counterpart of {@link #parts}. */
    private Optional<Object> containerValue(ValueContainer<?> container, List<Expression> arguments, SourceNode node) {
        List<Object> parts = new ArrayList<>(arguments.size());
        for (Expression argument : arguments) {
            Optional<Object> part = readAny(node.child(argument));
            if (part.isEmpty()) return Optional.empty();
            parts.add(part.get());
        }
        return Optional.ofNullable(container.build(parts));
    }

    /** {@code component.build(read parts)}, all or nothing, with the plugin's code contained. */
    private Optional<Object> build(Call call, List<Object> leading, List<Expression> arguments, SourceNode node) {
        List<Class<?>> types = ValueGrammar.componentTypesOf(call.component());
        int count = leading.size() + arguments.size();
        List<Object> parts = new ArrayList<>(leading);
        for (int i = 0; i < arguments.size(); i++) {
            int index = leading.size() + i;
            Optional<Object> part = readPart(ValueGrammar.partType(types, index, count), node.child(arguments.get(i)));
            if (part.isEmpty()) return Optional.empty();
            parts.add(part.get());
        }
        try {
            return Optional.ofNullable(call.component().build(List.copyOf(parts)));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** {@code Owner.NAME}: an enum constant, or a public static final field of a declared class. */
    private Optional<Object> constant(Name owner, String name, Class<?> expected) {
        if (expected != null && expected.isEnum() && Factory.names(owner, expected)) {
            Optional<Object> constant = enumConstant(expected, name);
            if (constant.isPresent()) return constant;
        }
        for (Class<?> type : enums) {
            if (!Factory.names(owner, type)) continue;
            Optional<Object> constant = enumConstant(type, name);
            if (constant.isPresent()) return constant;
        }
        for (Class<?> type : declared) {
            if (type.isEnum() || !Factory.names(owner, type)) continue;
            try {
                Field field = type.getField(name);
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                        || !field.getDeclaringClass().equals(type)) continue;
                if (expected != null && !boxed(expected).isAssignableFrom(boxed(field.getType()))) continue;
                return Optional.ofNullable(field.get(null));
            } catch (NoSuchFieldException | IllegalAccessException | RuntimeException | LinkageError e) {
                // Not a constant this class declares, or its initialiser threw: declined.
            }
        }
        return Optional.empty();
    }

    private static Optional<Object> enumConstant(Class<?> type, String name) {
        Object[] constants = type.getEnumConstants();
        if (constants == null) return Optional.empty();
        for (Object each : constants) if (((Enum<?>) each).name().equals(name)) return Optional.of(each);
        return Optional.empty();
    }

    // ---- plumbing --------------------------------------------------------------------------------------------

    /** Whether a value {@code call} builds can be an {@code expected}. */
    private static boolean assignable(Class<?> expected, Call call) {
        Class<?> built = ValueGrammar.classOf(call.component());
        return built != null && (expected == null || boxed(expected).isAssignableFrom(built));
    }

    private boolean isHostContainer(Class<?> type) {
        for (ValueContainer<?> container : grammar.containers()) if (container.type() == type) return true;
        return false;
    }

    private static Expression unwrap(Expression expression) {
        Expression out = expression;
        while (out instanceof ParenthesizedExpression parenthesized) out = parenthesized.getExpression();
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Expression> arguments(MethodInvocation call) {
        return (List<Expression>) call.arguments();
    }

    @SuppressWarnings("unchecked")
    private static List<Expression> arguments(ClassInstanceCreation creation) {
        return (List<Expression>) creation.arguments();
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }
}
