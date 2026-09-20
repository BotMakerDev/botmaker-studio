package com.botmaker.studio.project.models;

import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueType;

import java.util.List;

/**
 * The type of one node of a plugin's model, as the host resolved it off a {@link Class}.
 *
 * <p><b>Why this is not {@code ValueForm}.</b> {@code ValueForm} describes a type as a bot's source
 * <em>writes</em> it — a name, resolved against nothing, because the host reading a {@code @Param} field has
 * no bindings. A plugin's model arrives the other way round: the host holds the {@link Class} itself and a
 * live instance of it, so a component's type is a resolved class and not a spelling. Two of the five cases
 * here have no {@code ValueForm} at all — an enum constant and a record the plugin declares are legal model
 * types ({@code docs/refactor/33-plugin-java.md} §<i>The record grammar</i>) and neither is catalogued.
 *
 * <p>A form exists only for a type the grammar accepted, so every case below can be written. Refusal happens
 * once, at {@link ModelGrammar#refusal}, and what comes out the other side is writable by construction.
 */
public sealed interface ModelForm {

    /** {@code String}, a primitive or a box: written as the Java literal, read back as the literal. */
    record Builtin(Class<?> type) implements ModelForm {}

    /** An enum: written {@code Owner.CONSTANT}, read back by {@code valueOf}. */
    record Constant(Class<?> type) implements ModelForm {}

    /** A leaf some plugin registered, whose whole grammar is its own {@code ValueCodec}. */
    record Catalogued(ValueType type) implements ModelForm {}

    /**
     * A registered container over other forms — {@code List<E>}, {@code Map<K,V>}, anything a plugin
     * contributed. The arguments are the <em>type</em> arguments, so there are {@link
     * ValueContainer#arity()} of them however many values the instance holds.
     */
    record Composite(ValueContainer<?> container, List<ModelForm> arguments) implements ModelForm {

        public Composite {
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * Another record of the plugin's own.
     *
     * <p><b>Its components are deliberately not held here.</b> A model may be recursive — a node that holds
     * a list of nodes is an ordinary shape — and a form that expanded its components eagerly would not
     * terminate on one. The components are resolved at each node as the writer reaches it, which does
     * terminate, because the recursion is then over <em>values</em> and a value tree is finite.
     */
    record Declared(Class<?> type) implements ModelForm {}
}
