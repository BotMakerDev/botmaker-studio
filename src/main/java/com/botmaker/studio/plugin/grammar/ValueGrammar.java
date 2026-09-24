package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every character of value syntax, in both directions, for the types the bound plugins declare.
 *
 * <p><b>What this is.</b> The host half of {@code docs/refactor/32-generic-values.md} after the codec went:
 * one writer and one reader over the whole type tree, both total. A plugin declares a type once — a
 * {@link PluginType}, and a {@link ComponentType} beside it when its Java is a call — and never sees Java
 * source. The host writes {@code new Point(10, 20)} from {@code components(point)} and reads it back off the
 * JDT expression ({@link ExpressionReader}).
 *
 * <p><b>Both directions are trees</b> since 2026-09-24: a write builds the expression node by node
 * ({@link ValueWriter}) and answers a {@link JavaValue}, which a sink copies into its own file's tree. No
 * write concatenates Java, and no sink places text into a rewrite.
 *
 * <p><b>A value's type is a {@link java.lang.reflect.Type}</b> since 2026-09-24 ({@link ValueTypes}): the
 * plugin's own {@link Class}, a {@link ValueTypes.Parameterized} host container, a bot's own class, or an
 * unknown spelling. Nothing here turns a name back into a type; {@link #named} answers the one exact
 * question a resolver asks — which declared class has this canonical name.
 *
 * <h2>What a leaf is, decided in one place</h2>
 *
 * <ul>
 *   <li>a <b>JDK literal</b> — {@code String}, {@code char}, the primitives and their boxes:
 *       {@link JdkLiterals};</li>
 *   <li>a <b>component type</b> a plugin declares: a call, taken apart through {@code components} and put
 *       back through {@code build};</li>
 *   <li>an <b>enum</b>: {@code Owner.CONSTANT};</li>
 *   <li>a <b>chain</b> a person writes by hand ({@code Precision.TIGHT.minArea(400)}), read through a
 *       component whose factory is an instance method, and a {@code public static final} constant of a
 *       declared class ({@code Precision.TIGHT}). Both are read and never written;</li>
 *   <li>a type a plugin <b>declares with no component of its own</b> — the SDK's {@code CaptureSource}: read
 *       as whichever declared call builds one, and otherwise crossing as the source it is written as;</li>
 *   <li>and anything else, which is <b>unknown</b>: shown as written, never rewritten.</li>
 * </ul>
 *
 * <h2>Three rules</h2>
 *
 * <ul>
 *   <li><b>Empty means decline, and declining is not an error.</b> A guess compiles into a user's bot.</li>
 *   <li><b>A partial reading is not a reading.</b> One part that cannot be read empties the whole answer.</li>
 *   <li><b>Only what a declaration builds is read.</b></li>
 * </ul>
 *
 * <p><b>Plugin code is contained.</b> {@code components}, {@code build} and {@code fresh} are third-party
 * code; one that throws declines that value and nothing else.
 *
 * <p>Immutable. {@code PluginHost} builds one per bind; a test builds one from the types it needs.
 */
public final class ValueGrammar {

    private static final ValueGrammar EMPTY = new ValueGrammar(List.of(), List.of());

    /** Declared types, in plugin order, the first owner of a class name winning. */
    private final List<PluginType<?>> types;

    private final Map<String, PluginType<?>> typeByName;

    private final Map<String, ComponentType<?>> componentByName;

    /** The read half, over parsed expressions. Built last, from the registries above. */
    private final ExpressionReader reader;

    /** The write half, building expression trees over the same registries. */
    private final ValueWriter writer;

    private ValueGrammar(List<? extends PluginType<?>> types, List<? extends ComponentType<?>> components) {
        Map<String, PluginType<?>> byName = new LinkedHashMap<>();
        Map<String, ComponentType<?>> componentsByName = new LinkedHashMap<>();
        // Every enum a plugin mentions — declared, or a component's part — for reading an untyped constant.
        Set<Class<?>> enums = new LinkedHashSet<>();
        // Every class a plugin names as a type, the only classes whose constants are read.
        Set<Class<?>> declared = new LinkedHashSet<>();
        // Every way a declared value is written, receivers included: the reader's registry.
        List<ExpressionReader.Call> calls = new ArrayList<>();
        for (PluginType<?> type : types) {
            Class<?> cls = classOf(type);
            if (cls == null) continue;
            byName.putIfAbsent(JavaNames.canonical(cls), type);
            declared.add(cls);
            if (type instanceof ComponentType<?> component) {
                register(component, cls, componentsByName, calls);
                for (Class<?> part : componentTypesOf(component)) if (part.isEnum()) enums.add(part);
            }
            if (cls.isEnum()) enums.add(cls);
        }
        for (ComponentType<?> component : components) {
            Class<?> cls = classOf(component);
            if (cls == null) continue;
            declared.add(cls);
            register(component, cls, componentsByName, calls);
            for (Class<?> part : componentTypesOf(component)) if (part.isEnum()) enums.add(part);
        }
        this.types = List.copyOf(byName.values());
        this.typeByName = Map.copyOf(byName);
        this.componentByName = Map.copyOf(componentsByName);
        this.reader = new ExpressionReader(this, calls, List.copyOf(enums), List.copyOf(declared));
        this.writer = new ValueWriter(this);
    }

    /**
     * {@code component} as the reader sees it, and — the first time its class is seen, when its factory is
     * not a chain on part 0 — as the one the writer writes that class with.
     */
    private static void register(ComponentType<?> component, Class<?> cls, Map<String, ComponentType<?>> writers,
                                 List<ExpressionReader.Call> calls) {
        Optional<Factory> factory = Factory.of(component);
        if (factory.isEmpty()) return;
        calls.add(new ExpressionReader.Call(component, factory.get()));
        if (factory.get().kind() != Factory.Kind.RECEIVER) writers.putIfAbsent(JavaNames.canonical(cls), component);
    }

    /**
     * A grammar over these declarations. A {@link PluginType} that is also a {@link ComponentType} need not
     * be listed twice; the first declaration of a class name wins.
     */
    public static ValueGrammar of(List<? extends PluginType<?>> types, List<? extends ComponentType<?>> components) {
        return new ValueGrammar(types == null ? List.of() : types, components == null ? List.of() : components);
    }

    /** A grammar that knows only the JDK's literals and the host's containers. */
    public static ValueGrammar empty() {
        return EMPTY;
    }

    // ---- what is declared ------------------------------------------------------------------------------

    /** Every declared type, in plugin order — what a "new parameter" menu offers. */
    public List<PluginType<?>> types() {
        return types;
    }

    /** The host's containers — what a picker offers to wrap a type in. */
    public List<ValueContainer<?>> containers() {
        return ValueContainer.ALL;
    }

    /**
     * The class a value may be of whose canonical name is exactly {@code canonical} — a JDK literal, a
     * declared type or a component's class — or empty. <b>Exact</b>: the name comes from a binding or an
     * import, never from a spelling that merely ends the right way.
     */
    public Optional<Class<?>> named(String canonical) {
        if (canonical == null || canonical.isEmpty()) return Optional.empty();
        Optional<Class<?>> jdk = JdkLiterals.named(canonical);
        if (jdk.isPresent()) return jdk;
        PluginType<?> type = typeByName.get(canonical);
        if (type != null) return Optional.ofNullable(classOf(type));
        return Optional.ofNullable(componentClass(canonical));
    }

    /** Every canonical name {@link #named} answers — what a resolver without a binding tries a name against. */
    public List<String> names() {
        Set<String> out = new LinkedHashSet<>(typeByName.keySet());
        out.addAll(componentByName.keySet());
        out.addAll(JdkLiterals.names());
        return List.copyOf(out);
    }

    /** The declaration of {@code type}, or empty when no plugin declares it. */
    public Optional<PluginType<?>> type(Class<?> type) {
        return type == null ? Optional.empty() : Optional.ofNullable(typeByName.get(JavaNames.canonical(type)));
    }

    /**
     * Whether every leaf in {@code type} is one this grammar can read or a plugin declares. A type with an
     * unknown leaf anywhere is displayed and never rewritten.
     */
    public boolean known(Type type) {
        return firstUnknown(type) == null;
    }

    /**
     * The first leaf in {@code type} nothing reads or declares, as it is written — or {@code null} when every
     * one is known. Depth-first in written order.
     */
    public String firstUnknown(Type type) {
        return switch (type) {
            case null -> "";
            case Class<?> cls -> kindOf(cls) == Kind.UNKNOWN ? JavaNames.simple(cls) : null;
            case ValueTypes.Unknown unknown -> unknown.written();
            // A class the bot declares is not an unknown leaf: whether a value of it can be written is
            // BotRecords' answer. Its type arguments are still leaves.
            default -> firstUnknown(ValueTypes.arguments(type));
        };
    }

    private String firstUnknown(List<Type> types) {
        for (Type type : types) {
            String found = firstUnknown(type);
            if (found != null) return found;
        }
        return null;
    }

    // ---- reading ---------------------------------------------------------------------------------------

    /**
     * The value {@code initializer} writes, read as {@code type} — or empty.
     *
     * <p>Empty for an unknown type, a source this grammar did not write, and a class the bot declares, which
     * is {@code BotRecords}' to read.
     */
    public Optional<Object> valueOf(Type type, String initializer) {
        return SourceNode.parse(initializer).flatMap(node -> valueOf(type, node));
    }

    /** {@link #valueOf(Type, String)} over an expression already parsed, with the text it came from. */
    public Optional<Object> valueOf(Type type, SourceNode node) {
        return reader.read(type, node);
    }

    /**
     * The value {@code source} writes, typed by its own spelling — a literal, a declared call, a list, a map
     * — or empty. What a value with no declared type is read as.
     */
    public Optional<Object> valueOfAny(String source) {
        return SourceNode.parse(source).flatMap(this::valueOfAny);
    }

    /** {@link #valueOfAny(String)} over an expression already parsed. */
    public Optional<Object> valueOfAny(SourceNode node) {
        return reader.readAny(node);
    }

    // ---- writing ---------------------------------------------------------------------------------------
    //
    // Every write answers a JavaValue: a tree built node by node (ValueWriter), never Java concatenated as
    // text. "Qualified" names every class in full and needs no import; "spelled" names each by its simple name
    // and carries the imports that makes necessary — what a user's own file, which a person reads, is given.

    /**
     * {@code value} written as the initialiser a field of {@code type} takes, fully qualified — or empty when
     * any part of it cannot be written.
     */
    public Optional<JavaValue> initializer(Type type, Object value) {
        return write(type, value, new ValueWriter.Names(true));
    }

    /**
     * {@code value} written with every type by its simple name, and the imports that makes necessary — what
     * a slot in a user's own file is given, since that file is one a person reads.
     */
    public Optional<JavaValue> spell(Type type, Object value) {
        return write(type, value, new ValueWriter.Names(false));
    }

    /** {@code value} written by its own runtime class, fully qualified — the untyped counterpart of {@link #valueOfAny}. */
    public Optional<JavaValue> initializerOfAny(Object value) {
        ValueWriter.Names names = new ValueWriter.Names(true);
        return writer.any(value, names).map(names::value);
    }

    /** {@link #initializerOfAny}, with simple names and the imports they need. */
    public Optional<JavaValue> spellAny(Object value) {
        ValueWriter.Names names = new ValueWriter.Names(false);
        return writer.any(value, names).map(names::value);
    }

    private Optional<JavaValue> write(Type type, Object value, ValueWriter.Names names) {
        if (type == null) return Optional.empty();
        return writer.type(type, value, names).map(names::value);
    }

    // ---- a composite, one level at a time --------------------------------------------------------------

    /**
     * One part of a composite: the expression it is written as, and the type that expression is of.
     * {@link #source()} is how it was written, for showing a part nothing reads.
     */
    public record Part(Type form, SourceNode written) {

        public String source() {
            return written.source();
        }

        /** The part as it stands, to be written back untouched when nothing edited it. */
        public JavaValue kept() {
            return JavaValue.kept(written);
        }
    }

    /**
     * A container's initialiser taken apart one level: the parts as they are <em>written</em>, each with its
     * own type. Empty for anything that is not a call to the container's factory.
     */
    public Optional<List<Part>> partsOfInitializer(Type type, String initializer) {
        return SourceNode.parse(initializer).flatMap(node -> partsOfInitializer(type, node));
    }

    /** {@link #partsOfInitializer(Type, String)} over an expression already parsed. */
    public Optional<List<Part>> partsOfInitializer(Type type, SourceNode node) {
        return reader.parts(type, node);
    }

    /**
     * {@link #partsOfInitializer} written forwards: the call a container is written as, over parts that are
     * already values. Empty when a part is missing or the container cannot hold that many — an entry of three.
     */
    public Optional<JavaValue> compose(Type type, List<JavaValue> parts) {
        return writer.compose(type, parts);
    }

    // ---- a fresh value ---------------------------------------------------------------------------------

    /**
     * The Java a freshly declared field of {@code type} is initialised with, fully qualified, or empty when
     * there is none.
     *
     * <p>A leaf starts as its declaration's {@code fresh()}, written through this grammar; a type whose
     * starting value is a call the bot re-evaluates starts as a call to its {@code freshCall()}; a container
     * starts empty. A type nothing declares, and a class the bot declares, get nothing.
     */
    public Optional<JavaValue> freshInitializer(Type type) {
        ValueWriter.Names names = new ValueWriter.Names(true);
        return writer.fresh(type, names).map(names::value);
    }

    /** {@link #freshInitializer} with every type by its simple name and the imports that needs. */
    public Optional<JavaValue> freshSpelling(Type type) {
        ValueWriter.Names names = new ValueWriter.Names(false);
        return writer.fresh(type, names).map(names::value);
    }

    /**
     * The classes a file <em>declaring</em> a field of this type imports, in the order they are first
     * reached — what {@link ValueTypes#sourceName} names by its simple name.
     */
    public List<String> imports(Type type) {
        return ValueTypes.imports(type);
    }

    // ---- handing a value to a plugin -------------------------------------------------------------------

    /**
     * {@code value} as a {@code T}, or empty when it is not one — what {@code ValueContext.value(Class)}
     * answers. A primitive class asks for its box, since a value the host holds is always an object.
     */
    public static <T> Optional<T> as(Object value, Class<T> type) {
        if (value == null || type == null) return Optional.empty();
        Class<?> boxed = java.lang.invoke.MethodType.methodType(type).wrap().returnType();
        if (!boxed.isInstance(value)) return Optional.empty();
        @SuppressWarnings("unchecked")
        T cast = (T) value;
        return Optional.of(cast);
    }

    /**
     * Reads {@code source} as {@code type}, or by its own spelling when the type says nothing — the one
     * reading a value context does, so the canvas and the Parameters window cannot disagree.
     */
    public Optional<Object> read(Type type, String source) {
        return SourceNode.parse(source).flatMap(node -> read(type, node));
    }

    /** {@link #read(Type, String)} over an expression already parsed. */
    public Optional<Object> read(Type type, SourceNode node) {
        if (type instanceof ValueTypes.Unknown) return valueOfAny(node);
        return valueOf(type, node);
    }

    /**
     * {@link #spell}, or — when the type is one nothing here knows, as a slot the host could not resolve is —
     * the value by its own runtime class. Never the second when the type <em>is</em> known: an
     * {@code Integer} handed to a {@code String} field is refused, not written as {@code 3}.
     */
    public Optional<JavaValue> write(Type type, Object value) {
        if (type instanceof ValueTypes.Unknown) return spellAny(value);
        return spell(type, value);
    }

    // ---- plumbing --------------------------------------------------------------------------------------

    private enum Kind { JDK, COMPONENT, ENUM, SOURCE, UNKNOWN }

    /** Every component that writes a class, in declaration order: the writer's fallback by {@code isInstance}. */
    java.util.Collection<ComponentType<?>> components() {
        return componentByName.values();
    }

    private Kind kindOf(Class<?> type) {
        if (JdkLiterals.handles(type)) return Kind.JDK;
        String name = JavaNames.canonical(type);
        if (componentByName.containsKey(name)) return Kind.COMPONENT;
        if (!typeByName.containsKey(name)) return Kind.UNKNOWN;
        return type.isEnum() ? Kind.ENUM : Kind.SOURCE;
    }

    /**
     * The declared type of part {@code i} of {@code count}. Positional when the counts agree; for a call
     * written with more arguments than declared types, the last type repeats — a varargs tail.
     */
    static Class<?> partType(List<Class<?>> declared, int i, int count) {
        if (declared.isEmpty()) return null;
        if (i < declared.size()) return declared.get(i);
        return count > declared.size() ? declared.getLast() : null;
    }

    /** The declaration of {@code type}, or {@code null}: for the reader. */
    PluginType<?> declaration(Class<?> type) {
        return typeByName.get(JavaNames.canonical(type));
    }

    /** The class a component registered under {@code canonicalName} builds, or {@code null}. */
    Class<?> componentClass(String canonicalName) {
        ComponentType<?> component = componentByName.get(canonicalName);
        return component == null ? null : classOf(component);
    }

    /** The component that writes {@code type}, or {@code null}: the writer's registry. */
    ComponentType<?> canonical(Class<?> type) {
        return type == null ? null : componentByName.get(JavaNames.canonical(type));
    }

    static Class<?> classOf(PluginType<?> type) {
        try {
            return type == null ? null : type.type();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static Class<?> classOf(ComponentType<?> type) {
        try {
            return type == null ? null : type.type();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static List<Class<?>> componentTypesOf(ComponentType<?> component) {
        try {
            List<Class<?>> declared = component.componentTypes();
            return declared == null ? List.of() : declared;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }
}
