package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
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
import java.util.ArrayList;
import java.util.List;
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

    Optional<Object> read(ValueForm form, SourceNode node) {
        if (form == null || node == null || node.node() == null) return Optional.empty();
        return switch (form) {
            case ValueForm.Leaf leaf -> readNamed(leaf.typeName(), node);
            case ValueForm.Of of -> parts(of, node).flatMap(parts -> {
                List<Object> values = new ArrayList<>(parts.size());
                for (ValueGrammar.Part part : parts) {
                    Optional<Object> value = read(part.form(), part.written());
                    if (value.isEmpty()) return Optional.empty();
                    values.add(value.get());
                }
                return Optional.ofNullable(of.container().build(values));
            });
            // A class the bot declares is BotRecords' to read.
            case ValueForm.Declared ignored -> Optional.empty();
        };
    }

    Optional<List<ValueGrammar.Part>> parts(ValueForm form, SourceNode node) {
        return form instanceof ValueForm.Of of && node != null && node.node() != null ? parts(of, node) : Optional.empty();
    }

    private Optional<List<ValueGrammar.Part>> parts(ValueForm.Of of, SourceNode node) {
        if (!(unwrap(node.node()) instanceof MethodInvocation call)) return Optional.empty();
        if (!of.container().factory().matches(call, false)) return Optional.empty();
        List<Expression> arguments = arguments(call);
        List<ValueForm> forms = of.container().partForms(of.arguments(), arguments.size());
        if (forms.size() != arguments.size()) return Optional.empty();
        List<ValueGrammar.Part> parts = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            parts.add(new ValueGrammar.Part(forms.get(i), node.child(arguments.get(i))));
        }
        return Optional.of(List.copyOf(parts));
    }

    private Optional<Object> readNamed(String typeName, SourceNode node) {
        String name = grammar.qualify(typeName);
        if (name == null) return Optional.empty();
        if (JdkLiterals.handles(name)) return JdkLiterals.read(name, unwrap(node.node()));
        PluginType<?> type = grammar.declaration(name);
        Class<?> cls = type == null ? grammar.componentClass(name) : ValueGrammar.classOf(type);
        if (cls == null) return Optional.empty();
        Optional<Object> read = value(node, cls, true);
        // A declared type nothing writes, such as an interface no call here constructs, crosses as written.
        if (read.isPresent() || type == null || cls.isEnum() || grammar.canonical(cls) != null) return read;
        return Optional.of(node.source());
    }

    /** One part of a call, read as the class its declaration gives it. */
    private Optional<Object> readPart(Class<?> type, SourceNode node) {
        if (type == null || isHostContainer(type)) return readAny(node);
        String name = JavaNames.canonical(type);
        if (JdkLiterals.handles(name)) return JdkLiterals.read(name, unwrap(node.node()));
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
            case QualifiedName qualified -> constant(qualified.getQualifier().getFullyQualifiedName(),
                    qualified.getName().getIdentifier(), expected);
            case FieldAccess access when access.getExpression() instanceof Name owner ->
                    constant(owner.getFullyQualifiedName(), access.getName().getIdentifier(), expected);
            case SimpleName simple when bare && expected != null && expected.isEnum() ->
                    enumConstant(expected, simple.getIdentifier());
            default -> expected != null && JdkLiterals.handles(expected)
                    ? JdkLiterals.read(JavaNames.canonical(expected), expression)
                    : JdkLiterals.readAny(expression);
        };
        return read.filter(value -> expected == null || boxed(expected).isInstance(value));
    }

    private Optional<Object> construct(SourceNode node, Class<?> expected) {
        ClassInstanceCreation creation = (ClassInstanceCreation) node.node();
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
        return Optional.empty();
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
    private Optional<Object> constant(String owner, String name, Class<?> expected) {
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
