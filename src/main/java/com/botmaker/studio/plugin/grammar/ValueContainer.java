package com.botmaker.studio.plugin.grammar;

import java.util.List;

/**
 * A composite the host itself takes apart and puts back — {@code List<E>}, {@code Map<K, V>} and the
 * {@code Map.Entry} a map is written out of.
 *
 * <p><b>Three, and closed, since 2026-09-22.</b> This was the contract's open container vocabulary, which a
 * plugin registered into a {@code ValueCatalog}. A plugin's composite is a {@code ComponentType} now — the
 * same idea with the wire encoding removed — and what is left here is only what the <em>host</em> seeds so a
 * project with no plugin installed still has a list and a map. They differ from a {@code ComponentType} in
 * one way that matters: a type argument. {@code List<Duration>} tells the host what each element is, and a
 * component type's {@code componentTypes()} is a list of classes, which cannot say that.
 *
 * <h2>Nothing here is a string function</h2>
 *
 * <p>A container takes a composite apart into parts and puts it back together from parts. It never sees
 * Java source: the host writes {@code Owner.factory(p₁, …, pₙ)} once for all of them and reads it back off
 * the parsed expression.
 *
 * <h2>Arity counts types; parts count values</h2>
 *
 * <p>{@link #arity()} is how many type arguments a form supplies. {@link #parts} returns <em>values</em>, so
 * its length is the size of the composite. {@link #partForms} is what gives each part a static type, which is
 * what keeps the recursion typed all the way down.
 *
 * @param <C> the composite's own type
 */
public interface ValueContainer<C> {

    /** {@code java.util.List<E>} — {@code List.of(e₁, …)}. */
    ValueContainer<List<?>> LIST = new HostContainers.ListContainer();

    /**
     * {@code java.util.Map<K, V>} — {@code Map.ofEntries(Map.entry(k, v), …)}, always.
     *
     * <p>Never {@code Map.of}, which takes alternating keys and values and stops at ten pairs. One spelling
     * means one rule for the writer and one for the reader, and no behaviour change at the eleventh entry.
     */
    ValueContainer<java.util.Map<?, ?>> MAP = new HostContainers.MapContainer();

    /** {@code java.util.Map.Entry<K, V>} — {@code Map.entry(k, v)}. What a {@link #MAP}'s parts are. */
    ValueContainer<java.util.Map.Entry<?, ?>> ENTRY = new HostContainers.EntryContainer();

    /** All three, in the order a picker offers to wrap a form in them. */
    List<ValueContainer<?>> ALL = List.of(LIST, MAP, ENTRY);

    /** The class this container is. */
    Class<?> type();

    /** How many type arguments a form supplies: one for {@code List<E>}, two for {@code Map<K, V>}. */
    int arity();

    /**
     * What the container is written with: a static JDK method — {@code List.of}, {@code Map.ofEntries},
     * {@code Map.entry}, the last declared on {@code Map} rather than on the entry.
     */
    Factory factory();

    /**
     * This composite's parts, in the order they should be written.
     *
     * <p>Order is the container's to decide and it must be stable: it is what a diff of the user's file
     * shows, and an order that varies per run rewrites a file for no reason.
     */
    List<Object> parts(C value);

    /** {@link #parts} read backwards. Given parts this container produced, the composite they came from. */
    C build(List<Object> parts);

    /**
     * The static type of each part, given this form's type arguments and how many parts there are.
     *
     * <p>{@code List<E>} answers {@code parts} copies of {@code E}; {@code Map<K, V>} answers {@code parts}
     * copies of {@code Entry<K, V>}; an entry answers {@code [K, V]}.
     */
    List<ValueForm> partForms(List<ValueForm> arguments, int parts);

    // ---- derived -----------------------------------------------------------------------------------------

    /** How a declaration writes the type: {@code java.util.List}, {@code java.util.Map.Entry}. */
    default String sourceName() {
        return JavaNames.canonical(type());
    }

    /** The class a file importing this container names. */
    default String importName() {
        return JavaNames.importName(type());
    }

    /** What a menu calls wrapping a form in this container. */
    default String label() {
        return type().getSimpleName();
    }

    /**
     * {@link #parts} against a value the host holds as {@code Object}. The one unchecked cast, kept here so
     * no caller writes its own; sound because only a value of {@link #type()} is ever passed.
     */
    @SuppressWarnings("unchecked")
    default List<Object> partsOf(Object value) {
        return parts((C) value);
    }
}
