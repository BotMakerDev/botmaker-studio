package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * source. The host writes {@code new Point(10, 20)} from {@code components(point)} and reads it back by
 * matching the prefix and splitting at depth zero. It replaced {@code ValueCatalog}'s grammar half, which
 * did the same walk through a codec per leaf; its registry half (ids, codecs, clashes, the stored-text
 * bridges) was deleted rather than moved.
 *
 * <h2>What a leaf is, decided in one place</h2>
 *
 * <ul>
 *   <li>a <b>JDK literal</b> — {@code String}, {@code char}, the primitives and their boxes:
 *       {@link JdkLiterals};</li>
 *   <li>a <b>component type</b> a plugin declares: a call, taken apart through {@code components} and put
 *       back through {@code build};</li>
 *   <li>an <b>enum</b>: {@code Owner.CONSTANT};</li>
 *   <li>a type a plugin <b>declares with no component of its own</b> — the SDK's {@code CaptureSource},
 *       an interface: it is read as whichever declared call builds one of it
 *       ({@code CaptureSource.window("Game")}), and otherwise crosses as the source it is written as;</li>
 *   <li>and anything else, which is <b>unknown</b>: shown as written, never rewritten.</li>
 * </ul>
 *
 * <p>Inside a component the fourth case widens. A component's type is a {@link Class} the plugin handed
 * over, and one nothing declares — an {@code ActivityBody}, written {@code Collect::body} — crosses as its
 * source too. The plugin said that is what it is; outside a component nobody did.
 *
 * <h2>Three rules, all older than this class</h2>
 *
 * <ul>
 *   <li><b>Empty means decline, and declining is not an error.</b> A guess compiles into a user's bot.</li>
 *   <li><b>A partial reading is not a reading.</b> One part that cannot be read empties the whole answer, so
 *       a map with one unreadable value is shown whole and untouched rather than silently losing an
 *       entry.</li>
 *   <li><b>The split is not a parser.</b> It reads back what this wrote; anything else answers empty.</li>
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

    /** Every enum a plugin mentions — declared, or a component's part — for reading an untyped constant. */
    private final List<Class<?>> enums;

    private ValueGrammar(List<? extends PluginType<?>> types, List<? extends ComponentType<?>> components) {
        Map<String, PluginType<?>> byName = new LinkedHashMap<>();
        Map<String, ComponentType<?>> componentsByName = new LinkedHashMap<>();
        Set<Class<?>> enumSet = new LinkedHashSet<>();
        for (PluginType<?> type : types) {
            Class<?> cls = classOf(type);
            if (cls == null) continue;
            byName.putIfAbsent(JavaNames.canonical(cls), type);
            if (type instanceof ComponentType<?> component) componentsByName.putIfAbsent(JavaNames.canonical(cls), component);
            if (cls.isEnum()) enumSet.add(cls);
        }
        for (ComponentType<?> component : components) {
            Class<?> cls = classOf(component);
            if (cls == null) continue;
            componentsByName.putIfAbsent(JavaNames.canonical(cls), component);
            for (Class<?> part : componentTypesOf(component)) if (part.isEnum()) enumSet.add(part);
        }
        this.types = List.copyOf(byName.values());
        this.typeByName = Map.copyOf(byName);
        this.componentByName = Map.copyOf(componentsByName);
        this.enums = List.copyOf(enumSet);
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

    /** The host's containers — what a picker offers to wrap a form in. */
    public List<ValueContainer<?>> containers() {
        return ValueContainer.ALL;
    }

    /**
     * The container a written type name means — {@code List}, {@code java.util.List}, {@code Map.Entry} —
     * or empty. A simple name is matched against the canonical one's trailing segments, so a file that
     * imports {@code Map} and one that spells it out both read.
     */
    public Optional<ValueContainer<?>> containerForJava(String written) {
        String name = written == null ? "" : written.strip();
        if (name.isEmpty()) return Optional.empty();
        for (ValueContainer<?> candidate : ValueContainer.ALL) {
            String source = candidate.sourceName();
            if (source.equals(name) || source.endsWith("." + name)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * The canonical name a written leaf means — {@code Duration} answers {@code java.time.Duration} when a
     * plugin declares it, {@code String} answers {@code java.lang.String} — or {@code null} when nothing
     * reads or declares it.
     *
     * <p>Matched on trailing segments as well as whole, because {@code Duration} and
     * {@code java.time.Duration} are the same field written two ways and only the source says which. A name
     * two plugins both end with resolves to the first in plugin order, as every other lookup here does.
     */
    public String qualify(String written) {
        if (written == null || written.isBlank()) return null;
        String name = written.strip();
        String jdk = JdkLiterals.canonical(name);
        if (jdk != null) return jdk;
        if (typeByName.containsKey(name) || componentByName.containsKey(name)) return name;
        for (String known : typeByName.keySet()) if (known.endsWith("." + name)) return known;
        for (String known : componentByName.keySet()) if (known.endsWith("." + name)) return known;
        return null;
    }

    /** The declaration of the type a written name means, or empty when no plugin declares it. */
    public Optional<PluginType<?>> type(String written) {
        String name = qualify(written);
        return Optional.ofNullable(name == null ? null : typeByName.get(name));
    }

    /**
     * Whether every leaf in {@code form} is one this grammar can read or a plugin declares. A form with an
     * unknown leaf anywhere is displayed and never rewritten.
     */
    public boolean known(ValueForm form) {
        return firstUnknown(form) == null;
    }

    /**
     * The first leaf in {@code form} nothing reads or declares, as it is written — or {@code null} when every
     * one is known. Depth-first in written order, so the reason a cell gives names the argument a reader's
     * eye reaches first.
     */
    public String firstUnknown(ValueForm form) {
        return switch (form) {
            case null -> "";
            case ValueForm.Leaf leaf -> kindOf(leaf.typeName()) == Kind.UNKNOWN ? leaf.typeName() : null;
            case ValueForm.Of of -> firstUnknown(of.arguments());
            // A declared class is not an unknown leaf: the bot does declare it, and whether a value of it
            // can be written is BotRecords' answer. Its type arguments are still leaves.
            case ValueForm.Declared declared -> firstUnknown(declared.arguments());
        };
    }

    private String firstUnknown(List<ValueForm> forms) {
        for (ValueForm form : forms) {
            String found = firstUnknown(form);
            if (found != null) return found;
        }
        return null;
    }

    // ---- reading ---------------------------------------------------------------------------------------

    /**
     * The value {@code initializer} writes, read as {@code form} — or empty.
     *
     * <p>Empty for an unknown type, a source this grammar did not write, and a class the bot declares, which
     * is {@code BotRecords}' to read. The host then shows the initialiser as it stands, read-only — which is
     * what makes an unbounded type tree safe rather than dangerous.
     */
    public Optional<Object> valueOf(ValueForm form, String initializer) {
        if (form == null || initializer == null || initializer.isBlank()) return Optional.empty();
        String source = initializer.strip();
        return switch (form) {
            case ValueForm.Leaf leaf -> readNamed(leaf.typeName(), source);
            case ValueForm.Of of -> {
                Optional<List<String>> split =
                        SourceSplit.arguments(source, of.container().factorySource());
                if (split.isEmpty()) yield Optional.empty();
                List<String> written = split.get();
                List<ValueForm> forms = of.container().partForms(of.arguments(), written.size());
                if (forms.size() != written.size()) yield Optional.empty();
                List<Object> parts = new ArrayList<>(written.size());
                for (int i = 0; i < written.size(); i++) {
                    Optional<Object> part = valueOf(forms.get(i), written.get(i));
                    if (part.isEmpty()) yield Optional.empty();
                    parts.add(part.get());
                }
                yield Optional.ofNullable(of.container().build(parts));
            }
            case ValueForm.Declared ignored -> Optional.empty();
        };
    }

    /**
     * The value {@code source} writes, typed by its own spelling — a literal, a declared call, a list, a map
     * — or empty. What a value with no declared type is read as: a slot whose type the host could not
     * resolve, and the element of a list a component declares only as {@code List.class}.
     */
    public Optional<Object> valueOfAny(String source) {
        if (source == null || source.isBlank()) return Optional.empty();
        String trimmed = source.strip();
        Optional<Object> literal = JdkLiterals.readAny(trimmed);
        if (literal.isPresent()) return literal;
        for (ComponentType<?> component : componentByName.values()) {
            if (callArguments(component, trimmed, false).isPresent()) return readCall(component, trimmed);
        }
        for (ValueContainer<?> container : ValueContainer.ALL) {
            Optional<List<String>> split = SourceSplit.arguments(trimmed, container.factorySource(), false);
            if (split.isEmpty()) continue;
            List<Object> parts = new ArrayList<>();
            for (String written : split.get()) {
                Optional<Object> part = valueOfAny(written);
                if (part.isEmpty()) return Optional.empty();
                parts.add(part.get());
            }
            return Optional.ofNullable(container.build(parts));
        }
        for (Class<?> type : enums) {
            Optional<Object> constant = readEnum(type, trimmed, false);
            if (constant.isPresent()) return constant;
        }
        return Optional.empty();
    }

    private Optional<Object> readNamed(String typeName, String source) {
        String name = qualify(typeName);
        if (name == null) return Optional.empty();
        if (JdkLiterals.handles(name)) return JdkLiterals.read(name, source);
        ComponentType<?> component = componentByName.get(name);
        if (component != null) return readCall(component, source);
        PluginType<?> type = typeByName.get(name);
        if (type == null) return Optional.empty();
        Class<?> cls = classOf(type);
        if (cls != null && cls.isEnum()) return readEnum(cls, source, true);
        return readInstance(cls, source).or(() -> Optional.of(source));
    }

    /**
     * A type declared with no component of its own — an interface such as the SDK's {@code CaptureSource} —
     * read as whichever declared call {@code source} is, when that call builds one of it. Empty otherwise,
     * and the caller then hands the source over as written.
     */
    private Optional<Object> readInstance(Class<?> type, String source) {
        if (type == null || source.isBlank()) return Optional.empty();
        return valueOfAny(source).filter(type::isInstance);
    }

    /** One component of a call, read as the class its declaration says it is. */
    private Optional<Object> readClass(Class<?> type, String source) {
        if (type == null) return valueOfAny(source);
        String name = JavaNames.canonical(type);
        if (JdkLiterals.handles(name)) return JdkLiterals.read(name, source);
        ComponentType<?> component = componentByName.get(name);
        if (component != null) return readCall(component, source);
        if (type.isEnum()) return readEnum(type, source, true);
        if (isHostContainer(type)) return valueOfAny(source);
        // A part typed as an interface — a region's CaptureSource — is whichever declared call builds one.
        // Nothing else reads it and the plugin said this is what the part is, so it crosses as it is written.
        return readInstance(type, source)
                .or(() -> source.isBlank() ? Optional.empty() : Optional.of(source.strip()));
    }

    private Optional<Object> readCall(ComponentType<?> component, String source) {
        Optional<List<String>> split = callArguments(component, source, true);
        if (split.isEmpty()) return Optional.empty();
        List<String> written = split.get();
        List<Class<?>> declared = componentTypesOf(component);
        List<Object> parts = new ArrayList<>(written.size());
        for (int i = 0; i < written.size(); i++) {
            Optional<Object> part = readClass(partType(declared, i, written.size()), written.get(i));
            if (part.isEmpty()) return Optional.empty();
            parts.add(part.get());
        }
        try {
            return Optional.ofNullable(component.build(List.copyOf(parts)));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /**
     * The arguments of the call {@code component} writes, or empty when {@code source} is not that call.
     * {@code bare} accepts a static import's bare factory name, which only a caller that already knows the
     * type may.
     */
    private static Optional<List<String>> callArguments(ComponentType<?> component, String source, boolean bare) {
        String factory = factoryOf(component);
        Class<?> type = classOf(component);
        if (type == null) return Optional.empty();
        if (factory.isEmpty()) return SourceSplit.constructorArguments(source, JavaNames.canonical(type));
        return SourceSplit.arguments(source, JavaNames.canonical(ownerOf(component)) + "." + factory, bare);
    }

    /**
     * {@code Owner.CONSTANT}, {@code com.x.Owner.CONSTANT}, or — when {@code bare}, for a caller that knows
     * the type — {@code CONSTANT} alone, which a static import writes.
     */
    private static Optional<Object> readEnum(Class<?> type, String source, boolean bare) {
        String trimmed = source.strip();
        int dot = trimmed.lastIndexOf('.');
        String constant = dot < 0 ? trimmed : trimmed.substring(dot + 1);
        if (dot < 0 && !bare) return Optional.empty();
        if (dot >= 0) {
            String owner = trimmed.substring(0, dot);
            String canonical = JavaNames.canonical(type);
            if (!canonical.equals(owner) && !canonical.endsWith("." + owner)) return Optional.empty();
        }
        Object[] constants = type.getEnumConstants();
        if (constants == null) return Optional.empty();
        for (Object each : constants) {
            if (((Enum<?>) each).name().equals(constant)) return Optional.of(each);
        }
        return Optional.empty();
    }

    // ---- writing ---------------------------------------------------------------------------------------

    /** Java source and the imports it needs — empty when it was written fully qualified. */
    public record Written(String source, List<String> imports) {

        public Written {
            source = source == null ? "" : source;
            imports = imports == null ? List.of() : List.copyOf(imports);
        }
    }

    /**
     * {@code value} written as the initialiser a field of {@code form} takes, fully qualified — or empty when
     * any part of it cannot be written. Qualified so it compiles wherever it lands; {@link #spell} is the
     * spelling for a file that will arrange its own imports.
     */
    public Optional<String> initializer(ValueForm form, Object value) {
        return write(form, value, new Names(true)).map(Written::source);
    }

    /**
     * {@code value} written with every type by its simple name, and the imports that makes necessary — what
     * a slot in a user's own file is given, since that file is one a person reads.
     */
    public Optional<Written> spell(ValueForm form, Object value) {
        return write(form, value, new Names(false));
    }

    /** {@code value} written by its own runtime class, fully qualified — the untyped counterpart of {@link #valueOfAny}. */
    public Optional<String> initializerOfAny(Object value) {
        return writeAny(value, new Names(true));
    }

    /** {@link #initializerOfAny}, with simple names and the imports they need. */
    public Optional<Written> spellAny(Object value) {
        Names names = new Names(false);
        return writeAny(value, names).map(source -> new Written(source, names.imports()));
    }

    private Optional<Written> write(ValueForm form, Object value, Names names) {
        if (form == null) return Optional.empty();
        return writeForm(form, value, names).map(source -> new Written(source, names.imports()));
    }

    private Optional<String> writeForm(ValueForm form, Object value, Names names) {
        return switch (form) {
            case ValueForm.Leaf leaf -> writeNamed(leaf.typeName(), value, names);
            case ValueForm.Of of -> {
                if (value == null || !of.container().type().isInstance(value)) yield Optional.empty();
                List<Object> parts = of.container().partsOf(value);
                List<ValueForm> forms = of.container().partForms(of.arguments(), parts.size());
                if (forms.size() != parts.size()) yield Optional.empty();
                List<String> written = new ArrayList<>(parts.size());
                for (int i = 0; i < parts.size(); i++) {
                    Optional<String> part = writeForm(forms.get(i), parts.get(i), names);
                    if (part.isEmpty()) yield Optional.empty();
                    written.add(part.get());
                }
                yield Optional.of(names.of(of.container().factoryOwner()) + "." + of.container().factory()
                        + "(" + String.join(", ", written) + ")");
            }
            // A class the bot declares is BotRecords' to write: only the host's view of the bot's own source
            // knows its canonical constructor.
            case ValueForm.Declared ignored -> Optional.empty();
        };
    }

    private Optional<String> writeNamed(String typeName, Object value, Names names) {
        String name = qualify(typeName);
        if (name == null || value == null) return Optional.empty();
        if (JdkLiterals.handles(name)) return JdkLiterals.write(name, value);
        ComponentType<?> component = componentByName.get(name);
        if (component != null) return writeCall(component, value, names);
        PluginType<?> type = typeByName.get(name);
        if (type == null) return Optional.empty();
        Class<?> cls = classOf(type);
        if (cls != null && cls.isEnum()) return writeEnum(cls, value, names);
        return writeInstance(cls, value, names);
    }

    /**
     * {@link #readInstance} forwards: a value of a declared class is written through the component of its
     * own runtime class, and a {@code String} is source handed over as written.
     */
    private Optional<String> writeInstance(Class<?> type, Object value, Names names) {
        if (value instanceof String source) return source.isBlank() ? Optional.empty() : Optional.of(source.strip());
        return type != null && type.isInstance(value) ? writeAny(value, names) : Optional.empty();
    }

    private Optional<String> writeClass(Class<?> type, Object value, Names names) {
        if (value == null) return Optional.empty();
        if (type == null) return writeAny(value, names);
        String name = JavaNames.canonical(type);
        if (JdkLiterals.handles(name)) return JdkLiterals.write(name, value);
        ComponentType<?> component = componentByName.get(name);
        if (component != null) return writeCall(component, value, names);
        if (type.isEnum()) return writeEnum(type, value, names);
        if (isHostContainer(type)) return writeAny(value, names);
        return writeInstance(type, value, names);
    }

    private Optional<String> writeAny(Object value, Names names) {
        if (value == null) return Optional.empty();
        Optional<String> literal = JdkLiterals.writeAny(value);
        if (literal.isPresent()) return literal;
        if (value instanceof Enum<?> constant) return writeEnum(constant.getDeclaringClass(), value, names);
        for (ValueContainer<?> container : ValueContainer.ALL) {
            if (!container.type().isInstance(value)) continue;
            List<String> written = new ArrayList<>();
            for (Object part : container.partsOf(value)) {
                Optional<String> spelled = writeAny(part, names);
                if (spelled.isEmpty()) return Optional.empty();
                written.add(spelled.get());
            }
            return Optional.of(names.of(container.factoryOwner()) + "." + container.factory()
                    + "(" + String.join(", ", written) + ")");
        }
        ComponentType<?> exact = componentByName.get(JavaNames.canonical(value.getClass()));
        if (exact != null) return writeCall(exact, value, names);
        for (ComponentType<?> component : componentByName.values()) {
            Class<?> type = classOf(component);
            if (type != null && type.isInstance(value)) return writeCall(component, value, names);
        }
        return Optional.empty();
    }

    private Optional<String> writeCall(ComponentType<?> component, Object value, Names names) {
        Class<?> type = classOf(component);
        if (type == null || !type.isInstance(value)) return Optional.empty();
        List<Object> parts;
        try {
            parts = component.componentsOf(value);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        if (parts == null) return Optional.empty();
        List<Class<?>> declared = componentTypesOf(component);
        List<String> written = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Optional<String> part = writeClass(partType(declared, i, parts.size()), parts.get(i), names);
            if (part.isEmpty()) return Optional.empty();
            written.add(part.get());
        }
        String arguments = "(" + String.join(", ", written) + ")";
        String factory = factoryOf(component);
        return Optional.of(factory.isEmpty()
                ? "new " + names.of(type) + arguments
                : names.of(ownerOf(component)) + "." + factory + arguments);
    }

    private static Optional<String> writeEnum(Class<?> type, Object value, Names names) {
        return value instanceof Enum<?> constant && type.isInstance(value)
                ? Optional.of(names.of(type) + "." + constant.name())
                : Optional.empty();
    }

    // ---- a composite, one level at a time --------------------------------------------------------------

    /** One part of a composite: the source it is written as, and the form that source is of. */
    public record Part(ValueForm form, String initializer) {}

    /**
     * A container's initialiser taken apart one level: the parts as they are <em>written</em>, each with its
     * own form. Empty for anything that is not a call to the container's factory.
     *
     * <p>Source rather than values, because a cell edits one part at a time and a part it cannot read must be
     * shown as written rather than dropped — a map with one unreadable value still draws as a map, with that
     * one row read-only. Recursion is the caller's: a {@code Map}'s parts are entries, passed back in.
     */
    public Optional<List<Part>> partsOfInitializer(ValueForm form, String initializer) {
        if (!(form instanceof ValueForm.Of of) || initializer == null) return Optional.empty();
        Optional<List<String>> split = SourceSplit.arguments(initializer.strip(), of.container().factorySource());
        if (split.isEmpty()) return Optional.empty();
        List<String> written = split.get();
        List<ValueForm> forms = of.container().partForms(of.arguments(), written.size());
        if (forms.size() != written.size()) return Optional.empty();
        List<Part> parts = new ArrayList<>(written.size());
        for (int i = 0; i < written.size(); i++) parts.add(new Part(forms.get(i), written.get(i)));
        return Optional.of(List.copyOf(parts));
    }

    /**
     * {@link #partsOfInitializer} written forwards: the call a container spells over parts already written as
     * source. Empty when a part is blank or the container cannot hold that many parts — an entry of three.
     */
    public Optional<String> initializerOfParts(ValueForm form, List<String> parts) {
        if (!(form instanceof ValueForm.Of of) || parts == null) return Optional.empty();
        if (parts.stream().anyMatch(part -> part == null || part.isBlank())) return Optional.empty();
        if (of.container().partForms(of.arguments(), parts.size()).size() != parts.size()) return Optional.empty();
        return Optional.of(of.container().factorySource() + "(" + String.join(", ", parts) + ")");
    }

    // ---- a fresh value ---------------------------------------------------------------------------------

    /**
     * The Java a freshly declared field of {@code form} is initialised with, or empty when there is none.
     *
     * <p>A leaf starts as its declaration's {@code fresh()}, written through this grammar; a type whose
     * starting value is a call the bot re-evaluates starts as a call to its {@code freshCall()}; a container starts
     * empty — a blank entry in every new map is a value nobody chose. A type nothing declares, and a class
     * the bot declares, get nothing: a placeholder written into a user's file is a value they did not
     * choose, and a declaration with no initialiser is legal Java for every type.
     */
    public Optional<String> freshInitializer(ValueForm form) {
        return fresh(form, new Names(true)).map(Written::source);
    }

    /**
     * {@link #freshInitializer} with every type by its simple name and the imports that needs — for a
     * statement in a file a person reads. A {@code freshCall()} is spelled the same way:
     * {@code Vision.lastMatch()} and its import.
     */
    public Optional<Written> freshSpelling(ValueForm form) {
        return fresh(form, new Names(false));
    }

    private Optional<Written> fresh(ValueForm form, Names names) {
        return switch (form) {
            case null -> Optional.empty();
            case ValueForm.Leaf leaf -> {
                Optional<PluginType<?>> type = type(leaf.typeName());
                if (type.isEmpty()) yield Optional.empty();
                Object fresh;
                Method freshCall;
                try {
                    fresh = type.get().fresh();
                    freshCall = type.get().freshCall();
                } catch (RuntimeException | LinkageError e) {
                    yield Optional.empty();
                }
                if (fresh != null) {
                    Optional<Written> written = write(form, fresh, names);
                    if (written.isPresent()) yield written;
                }
                yield call(freshCall, type.get(), names);
            }
            case ValueForm.Of of -> write(form, of.container().build(List.of()), names);
            case ValueForm.Declared ignored -> Optional.empty();
        };
    }

    /**
     * {@code Owner.method()} for a type's {@code freshCall()}, or empty for none or one of the wrong shape.
     *
     * <p>The shape the contract states is checked again here rather than trusted: a call that takes
     * arguments, is not static or returns another type would be written into somebody's file and not
     * compile. {@code botmaker plugin validate} refuses the same plugin earlier; this is the host not
     * depending on that having run.
     */
    private static Optional<Written> call(Method method, PluginType<?> type, Names names) {
        if (method == null || !Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                || method.getParameterCount() != 0 || type.type() == null
                || !method.getReturnType().getName().equals(type.type().getName())) {
            return Optional.empty();
        }
        String source = names.of(method.getDeclaringClass()) + "." + method.getName() + "()";
        return Optional.of(new Written(source, names.imports()));
    }

    /**
     * The classes a file <em>declaring</em> a field of this form imports, in the order they are first
     * reached — what {@link ValueForm#sourceName()} names by its simple name. A host container is not among
     * them: the form writes it fully qualified.
     */
    public List<String> imports(ValueForm form) {
        Set<String> out = new LinkedHashSet<>();
        collectImports(form, out);
        out.remove("");
        return List.copyOf(out);
    }

    private void collectImports(ValueForm form, Set<String> out) {
        switch (form) {
            case null -> {
            }
            case ValueForm.Leaf leaf -> {
                String name = qualify(leaf.typeName());
                out.add(JavaNames.importName(name == null ? leaf.typeName() : name));
            }
            case ValueForm.Of of -> of.arguments().forEach(argument -> collectImports(argument, out));
            case ValueForm.Declared declared -> declared.arguments().forEach(argument -> collectImports(argument, out));
        }
    }

    // ---- handing a value to a plugin -------------------------------------------------------------------

    /**
     * {@code value} as a {@code T}, or empty when it is not one — what {@code ValueContext.value(Class)}
     * answers. A primitive class asks for its box, since a value the host holds is always an object.
     */
    public static <T> Optional<T> as(Object value, Class<T> type) {
        if (value == null || type == null) return Optional.empty();
        Class<?> boxed = type.isPrimitive() ? box(type) : type;
        if (!boxed.isInstance(value)) return Optional.empty();
        @SuppressWarnings("unchecked")
        T cast = (T) value;
        return Optional.of(cast);
    }

    private static Class<?> box(Class<?> primitive) {
        return switch (primitive.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            default -> primitive;
        };
    }

    /**
     * Reads {@code source} as {@code form}, falling back to its own spelling when the form says nothing —
     * the one reading a value context does, so the canvas and the Parameters window cannot disagree.
     */
    public Optional<Object> read(ValueForm form, String source) {
        if (form instanceof ValueForm.Leaf leaf && qualify(leaf.typeName()) == null) return valueOfAny(source);
        return valueOf(form, source);
    }

    /**
     * {@link #spell}, or — when the form names a type nothing here knows, as a slot the host could not
     * resolve does — the value by its own runtime class. Never the second when the form <em>is</em> known:
     * an {@code Integer} handed to a {@code String} field is refused, not written as {@code 3}.
     */
    public Optional<Written> write(ValueForm form, Object value) {
        if (form instanceof ValueForm.Leaf leaf && qualify(leaf.typeName()) == null) return spellAny(value);
        return spell(form, value);
    }

    // ---- plumbing --------------------------------------------------------------------------------------

    private enum Kind { JDK, COMPONENT, ENUM, SOURCE, UNKNOWN }

    private Kind kindOf(String typeName) {
        String name = qualify(typeName);
        if (name == null) return Kind.UNKNOWN;
        if (JdkLiterals.handles(name)) return Kind.JDK;
        if (componentByName.containsKey(name)) return Kind.COMPONENT;
        PluginType<?> type = typeByName.get(name);
        if (type == null) return Kind.UNKNOWN;
        Class<?> cls = classOf(type);
        return cls != null && cls.isEnum() ? Kind.ENUM : Kind.SOURCE;
    }

    /**
     * The declared type of part {@code i} of {@code count}. Positional when the counts agree; for a call
     * written with more arguments than declared types, the last type repeats — a varargs tail.
     */
    private static Class<?> partType(List<Class<?>> declared, int i, int count) {
        if (declared.isEmpty()) return null;
        if (i < declared.size()) return declared.get(i);
        return count > declared.size() ? declared.getLast() : null;
    }

    private static boolean isHostContainer(Class<?> type) {
        for (ValueContainer<?> container : ValueContainer.ALL) if (container.type() == type) return true;
        return false;
    }

    private static Class<?> classOf(PluginType<?> type) {
        try {
            return type == null ? null : type.type();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Class<?> classOf(ComponentType<?> type) {
        try {
            return type == null ? null : type.type();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static List<Class<?>> componentTypesOf(ComponentType<?> component) {
        try {
            List<Class<?>> declared = component.componentTypes();
            return declared == null ? List.of() : declared;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private static String factoryOf(ComponentType<?> component) {
        try {
            String factory = component.factory();
            return factory == null ? "" : factory.strip();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    private static Class<?> ownerOf(ComponentType<?> component) {
        try {
            Class<?> owner = component.factoryOwner();
            return owner == null ? component.type() : owner;
        } catch (RuntimeException | LinkageError e) {
            return classOf(component);
        }
    }

    /** How one write spells a type: qualified, or simple with the import collected. */
    private static final class Names {

        private final boolean qualified;
        private final Set<String> imports = new LinkedHashSet<>();

        Names(boolean qualified) {
            this.qualified = qualified;
        }

        String of(Class<?> type) {
            String importName = JavaNames.importName(type);
            if (importName.isEmpty()) return JavaNames.simple(type);
            if (qualified) return JavaNames.canonical(type);
            imports.add(importName);
            return JavaNames.simple(type);
        }

        List<String> imports() {
            return List.copyOf(imports);
        }
    }
}
