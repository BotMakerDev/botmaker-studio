package com.botmaker.studio.project.models;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a record is legal as a plugin's model, and — when it is — the form of every node in it.
 *
 * <p>The grammar is {@code docs/refactor/33-plugin-java.md} §<i>The record grammar</i>: a registered leaf, a
 * registered container of legal types, another legal record, an enum constant, {@code String}, the eight
 * primitives and their boxes. Everything else is refused, <b>with the component named</b>.
 *
 * <h2>Refused at registration, not at write</h2>
 *
 * <p>The first of the three stated requirements is <i>100% accurate compilation</i>, and this class is how
 * it is met: not by emitting carefully, but by making the set of things Studio will <em>accept</em> equal
 * the set it can write. A plugin whose model names an interface learns so the first time it runs, with the
 * component named, rather than the first time a user saves. {@link ModelWriter} therefore has no refusal
 * path for a type — only for a value, where {@code null} is the one thing a legal type can still hold.
 *
 * <h2>Pure, over a {@code Class}, and nothing else</h2>
 *
 * <p>No host state, no project, no JavaFX. What goes in is a class and a catalog; what comes out is a
 * refusal or a form. That is what makes both halves testable without a running Studio, and it is the same
 * discipline {@code project/params/} keeps — {@code JavaParameterSource} reads source text and knows nothing
 * of a window either.
 *
 * <h2>Builtins first, then the catalog</h2>
 *
 * <p>{@code String} and {@code int} are very probably registered leaves as well — plugin-basics registers
 * both — and this class still writes them as Java literals rather than through their codecs. The reason is
 * that a codec is typed: {@code ValueCodec<Long>} handed an {@code Integer} throws, and a component declared
 * {@code int} beside a catalogued type declared {@code long} is exactly the collision that would find it.
 * The builtin row of {@code 33}'s table exists for this, and taking it first means a primitive is written
 * from what the compiler guarantees rather than from what a plugin registered.
 */
public final class ModelGrammar {

    /**
     * Why a model was refused: the path to the offending component and what is wrong with it.
     *
     * @param path {@code Flow.nodes.activity} — the model's own name first, so a message can be read without
     *             knowing which record the walk started at
     */
    public record Refusal(String path, String reason) {}

    /** The ids the marker leaves below carry. Never registered, never written — see {@link #partForms}. */
    private static final String MARKER = "#";

    /** {@code String}, the eight primitives and their boxes: the one row of the table with no indirection. */
    private static final Set<Class<?>> BUILTINS = Set.of(
            String.class,
            boolean.class, byte.class, char.class, short.class, int.class, long.class, float.class,
            double.class,
            Boolean.class, Byte.class, Character.class, Short.class, Integer.class, Long.class, Float.class,
            Double.class);

    private ModelGrammar() {
    }

    /**
     * Empty when {@code type} is legal as a plugin model, or the first component that is not.
     *
     * <p>The top-level type must itself be a record. A plugin handing Studio anything else has not written a
     * model, and the message says so rather than naming a component it does not have.
     */
    public static Optional<Refusal> refusal(Class<?> type, ValueCatalog catalog) {
        if (type == null) return Optional.of(new Refusal("", "no type was given"));
        if (!type.isRecord()) {
            return Optional.of(new Refusal(type.getSimpleName(), "a model is a record, and this is not one"));
        }
        List<Refusal> refusals = new ArrayList<>(1);
        walk(type.getSimpleName(), type, catalog, new ArrayDeque<>(), refusals);
        return refusals.stream().findFirst();
    }

    /** Whether {@link #refusal} is empty — the question a registration asks, without the sentence. */
    public static boolean isLegal(Class<?> type, ValueCatalog catalog) {
        return refusal(type, catalog).isEmpty();
    }

    /**
     * The form of a type the grammar accepts, or empty when it does not accept it.
     *
     * <p>The reader and the writer both start here, so the two of them cannot disagree about what a
     * component means — which is the property that makes {@code 33}'s claim hold, that the writer's output is
     * the reader's whole input domain.
     */
    public static Optional<ModelForm> formOf(Type type, ValueCatalog catalog) {
        return Optional.ofNullable(walk("", type, catalog, new ArrayDeque<>(), new ArrayList<>()));
    }

    /**
     * The components of a declared record, in canonical-constructor order, each with its form.
     *
     * <p>Called by the writer at every {@link ModelForm.Declared} it reaches, which is why {@code Declared}
     * holds no components of its own: the expansion happens over a value that is finite rather than over a
     * type that may be recursive.
     */
    public static Optional<List<Component>> componentsOf(Class<?> type, ValueCatalog catalog) {
        if (type == null || !type.isRecord()) return Optional.empty();
        RecordComponent[] declared = type.getRecordComponents();
        List<Component> out = new ArrayList<>(declared.length);
        for (RecordComponent component : declared) {
            ModelForm form = walk("", component.getGenericType(), catalog, new ArrayDeque<>(),
                    new ArrayList<>());
            if (form == null) return Optional.empty();
            out.add(new Component(component, form));
        }
        return Optional.of(out);
    }

    /** One component of a model record: the reflected component, and the form its declared type means. */
    public record Component(RecordComponent component, ModelForm form) {}

    /**
     * The static form of each part of a composite, however many parts the instance holds.
     *
     * <p><b>The container is asked, never guessed.</b> A {@code List} answers <i>n</i> copies of its element
     * and a {@code Map} answers <i>n</i> entries, but a plugin's own container answers whatever it declared,
     * and the host has no business knowing which. {@link ValueContainer#partForms} states it — in terms of
     * {@code ValueForm}, because that is the contract's vocabulary — so this asks it with a marker leaf
     * standing in for each type argument and substitutes the real forms back into the answer. Whatever the
     * container composes out of its arguments comes back composed out of ours.
     *
     * <p>Empty when a container's answer names a class the bot declares, which a container cannot know about
     * and must not invent.
     */
    static Optional<List<ModelForm>> partForms(ModelForm.Composite composite, int parts) {
        ValueContainer<?> container = composite.container();
        List<ValueForm> markers = new ArrayList<>(container.arity());
        for (int i = 0; i < container.arity(); i++) {
            markers.add(ValueForm.of(ValueType.unknown(MARKER + i)));
        }
        List<ValueForm> shapes = container.partForms(markers, parts);
        if (shapes == null || shapes.size() != parts) return Optional.empty();

        List<ModelForm> out = new ArrayList<>(parts);
        for (ValueForm shape : shapes) {
            ModelForm substituted = substitute(shape, composite.arguments());
            if (substituted == null) return Optional.empty();
            out.add(substituted);
        }
        return Optional.of(out);
    }

    private static ModelForm substitute(ValueForm shape, List<ModelForm> arguments) {
        return switch (shape) {
            case null -> null;
            case ValueForm.Leaf leaf -> {
                int index = markerIndex(leaf.type().id());
                if (index < 0) yield new ModelForm.Catalogued(leaf.type());
                yield index < arguments.size() ? arguments.get(index) : null;
            }
            case ValueForm.Of of -> {
                List<ModelForm> inner = new ArrayList<>(of.arguments().size());
                for (ValueForm argument : of.arguments()) {
                    ModelForm one = substitute(argument, arguments);
                    if (one == null) yield null;
                    inner.add(one);
                }
                yield new ModelForm.Composite(of.container(), inner);
            }
            case ValueForm.Declared ignored -> null;
        };
    }

    private static int markerIndex(String id) {
        if (id == null || !id.startsWith(MARKER)) return -1;
        try {
            return Integer.parseInt(id.substring(MARKER.length()));
        } catch (NumberFormatException notAMarker) {
            return -1;
        }
    }

    /**
     * One node of the type walk: the form, or {@code null} with the first refusal recorded.
     *
     * <p>{@code seen} carries the records currently being walked, and a record already in it is accepted
     * without being walked again. A model is allowed to be recursive — a node holding a list of nodes is the
     * ordinary shape of a graph — and the alternative, refusing it, would refuse a legal thing to save
     * having to notice a cycle. A record that contains <em>itself</em> directly rather than through a
     * container is left to the writer, where it shows up as the {@code null} it must hold.
     */
    private static ModelForm walk(String path, Type type, ValueCatalog catalog, Deque<Class<?>> seen,
                                  List<Refusal> refusals) {
        switch (type) {
            case null -> {
                return refuse(path, "has no type", refusals);
            }
            case Class<?> raw when raw.isArray() -> {
                return refuse(path, "is an array; a list says the same thing and can be read back", refusals);
            }
            case Class<?> raw -> {
                return walkClass(path, raw, List.of(), catalog, seen, refusals);
            }
            case ParameterizedType parameterized
                    when parameterized.getRawType() instanceof Class<?> raw -> {
                return walkClass(path, raw, List.of(parameterized.getActualTypeArguments()), catalog, seen,
                        refusals);
            }
            default -> {
                return refuse(path, "is written " + type.getTypeName()
                        + ", which has no one meaning a generated file could write", refusals);
            }
        }
    }

    private static ModelForm walkClass(String path, Class<?> raw, List<Type> arguments, ValueCatalog catalog,
                                       Deque<Class<?>> seen, List<Refusal> refusals) {
        if (BUILTINS.contains(raw)) return new ModelForm.Builtin(raw);
        if (raw.isEnum()) return new ModelForm.Constant(raw);

        ValueCatalog known = catalog == null ? ValueCatalog.empty() : catalog;
        Optional<ValueContainer<?>> container = known.containerFor(raw);
        if (container.isPresent()) {
            return walkContainer(path, container.get(), arguments, known, seen, refusals);
        }
        Optional<ValueType> leaf = known.forJava(raw);
        if (leaf.isPresent()) return new ModelForm.Catalogued(leaf.get());

        if (raw.isRecord()) return walkRecord(path, raw, arguments, known, seen, refusals);

        return refuse(path, "is " + raw.getName()
                + ", which is neither a registered value type, a container, an enum nor a record", refusals);
    }

    private static ModelForm walkContainer(String path, ValueContainer<?> container, List<Type> arguments,
                                           ValueCatalog catalog, Deque<Class<?>> seen,
                                           List<Refusal> refusals) {
        if (arguments.size() != container.arity()) {
            return refuse(path, "is a raw " + container.sourceName()
                    + "; the type it holds has to be written down for a generated file to name it", refusals);
        }
        List<ModelForm> inner = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            ModelForm one = walk(path + "<" + i + ">", arguments.get(i), catalog, seen, refusals);
            if (one == null) return null;
            inner.add(one);
        }
        return new ModelForm.Composite(container, inner);
    }

    private static ModelForm walkRecord(String path, Class<?> raw, List<Type> arguments, ValueCatalog catalog,
                                        Deque<Class<?>> seen, List<Refusal> refusals) {
        if (!arguments.isEmpty() || raw.getTypeParameters().length > 0) {
            return refuse(path, "is the generic record " + raw.getSimpleName()
                    + "; a model's records are concrete, so that every component has one written type",
                    refusals);
        }
        if (seen.contains(raw)) return new ModelForm.Declared(raw);

        seen.push(raw);
        try {
            for (RecordComponent component : raw.getRecordComponents()) {
                String below = path.isEmpty() ? component.getName() : path + "." + component.getName();
                if (walk(below, component.getGenericType(), catalog, seen, refusals) == null) return null;
            }
        } finally {
            seen.pop();
        }
        return new ModelForm.Declared(raw);
    }

    private static ModelForm refuse(String path, String reason, List<Refusal> refusals) {
        if (refusals.isEmpty()) refusals.add(new Refusal(path, reason));
        return null;
    }
}
