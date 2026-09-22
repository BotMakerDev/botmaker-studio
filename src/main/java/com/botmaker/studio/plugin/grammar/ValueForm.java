package com.botmaker.studio.plugin.grammar;

import java.util.List;

/**
 * The type of a value, as a tree: a leaf type, a host container over other forms, or a generic class the bot
 * itself declares.
 *
 * <p>This replaced {@code ValueChoice}, a {@code ValueType} plus a four-constant {@code ValueShape}, deleted
 * on 2026-09-20. That pair could not say {@code Map<String, Duration>}, {@code List<List<Point>>}, or
 * anything else with two type arguments or two levels. A bot may perfectly well declare such a field — javac
 * accepts it — and the host read it as unknown and refused to edit it. The design is
 * {@code docs/refactor/32-generic-values.md}.
 *
 * <p><b>The host's, since 2026-09-22.</b> It was the contract's until then, and nothing outside the host
 * ever walked one: a plugin is handed a value through {@code ValueContext.value(Class)} and never sees the
 * form it was read as. A leaf held a {@code ValueType} — a persisted id with a codec — and holds the Java
 * type's name now, because the id and the codec went with the contract's value vocabulary and the class is
 * the identity.
 *
 * <p><b>Nesting is unbounded here.</b> A host caps what its <em>picker</em> offers, because a four-level
 * value cell is not drawable in a table row; nothing caps what may be read out of a user's file, displayed
 * and left intact. Read-only is a first-class outcome, not a failure path.
 *
 * <h2>Three cases, because there are three literal grammars</h2>
 *
 * <p>A {@link Declared} writes {@code new C<>(…)}; an {@link Of} writes whatever its {@link ValueContainer}
 * declares, which is a static factory; a {@link Leaf} is written by whatever {@link ValueGrammar} knows
 * about its type — a JDK literal, an enum constant, or a plugin's {@code ComponentType}. {@code List} is
 * not a {@code Declared} because it is an interface with no constructor — <b>not</b> because it is
 * generic, which {@code Declared} handles perfectly well.
 */
public sealed interface ValueForm {

    /**
     * One type with no type arguments.
     *
     * @param typeName the canonical name when the grammar resolved it ({@code java.time.Duration},
     *                 {@code int}, {@code java.lang.String}), otherwise the name exactly as written — which
     *                 is what a read-only cell says nobody declares
     */
    record Leaf(String typeName) implements ValueForm {

        public Leaf {
            typeName = typeName == null ? "" : typeName.strip();
        }
    }

    /**
     * A host container over other forms. The arity is the container's, and a wrong one is corrected rather
     * than stored, so that every reader gets the correction — a file, a fixture, a caller's literal.
     */
    record Of(ValueContainer<?> container, List<ValueForm> arguments) implements ValueForm {

        public Of {
            if (container == null) container = ValueContainer.LIST;
            arguments = fill(container.arity(), arguments);
        }

        /**
         * The element of a {@code List}, or the value of a {@code Map} — the last argument either way.
         */
        public ValueForm last() {
            return arguments.isEmpty() ? null : arguments.get(arguments.size() - 1);
        }
    }

    /**
     * A generic class the bot itself declares, by qualified name.
     *
     * <p><b>Not a {@link Leaf}</b>, deliberately. A bot's {@code Box<T>} is not declared by any plugin: a
     * second project's {@code Box} is a different class. What the host knows about it is its qualified name
     * and its type arguments, which is all it needs to write {@code new Box<>(…)} and read it back
     * positionally.
     *
     * @param arguments empty for a non-generic class, which is legal and common
     */
    record Declared(String qualifiedName, List<ValueForm> arguments) implements ValueForm {

        public Declared {
            if (qualifiedName == null) qualifiedName = "";
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }

    /**
     * {@code arguments}, padded with empty leaves or truncated to {@code arity}. Total: a caller that
     * supplies the wrong number gets a usable form rather than an exception, and the empty leaf it is padded
     * with is the state that already means <em>nothing declares this</em>.
     */
    private static List<ValueForm> fill(int arity, List<ValueForm> arguments) {
        List<ValueForm> given = arguments == null ? List.of() : arguments;
        if (given.size() == arity) return List.copyOf(given);
        ValueForm[] filled = new ValueForm[arity];
        for (int i = 0; i < arity; i++) {
            ValueForm argument = i < given.size() ? given.get(i) : null;
            filled[i] = argument == null ? new Leaf("") : argument;
        }
        return List.of(filled);
    }

    /** One free value of the type {@code typeName} names. */
    static ValueForm of(String typeName) {
        return new Leaf(typeName);
    }

    /** One free value of {@code type}, by its canonical name. */
    static ValueForm of(Class<?> type) {
        return new Leaf(JavaNames.canonical(type));
    }

    /** A list of {@code form}. */
    static ValueForm listOf(ValueForm form) {
        return new Of(ValueContainer.LIST, List.of(form));
    }

    /** A map from {@code key} to {@code value}. */
    static ValueForm mapOf(ValueForm key, ValueForm value) {
        return new Of(ValueContainer.MAP, List.of(key, value));
    }

    /**
     * How a declaration writes this type — {@code Duration}, {@code java.util.Map<String, Point>}.
     *
     * <p>A leaf is spelled by its simple name, and the import is what {@link ValueGrammar#imports} answers
     * separately. The containers themselves are written fully qualified, so a caller composing a declaration
     * never needs an import for the container.
     *
     * <p>Primitives are boxed inside angle brackets and only there, which is the one thing a type argument
     * needs from its leaf that a bare declaration does not.
     */
    default String sourceName() {
        return switch (this) {
            // A leaf read as written because nothing could name it — `java.util.Set<String>`, `int[]` — is
            // shown exactly as written: taking its package off would name a different type.
            case Leaf leaf when leaf.typeName().indexOf('<') >= 0 || leaf.typeName().indexOf('[') >= 0 ->
                    leaf.typeName();
            case Leaf leaf -> JavaNames.simple(leaf.typeName());
            case Of of -> of.container().sourceName()
                    + (of.arguments().isEmpty() ? "" : arguments(of.arguments()));
            case Declared declared -> declared.qualifiedName()
                    + (declared.arguments().isEmpty() ? "" : arguments(declared.arguments()));
        };
    }

    /** The class a file declaring this leaf imports, or {@code ""} — a primitive, {@code java.lang}. */
    default String importName() {
        return this instanceof Leaf leaf ? JavaNames.importName(leaf.typeName()) : "";
    }

    private static String arguments(List<ValueForm> forms) {
        StringBuilder out = new StringBuilder("<");
        for (int i = 0; i < forms.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(boxed(forms.get(i)));
        }
        return out.append('>').toString();
    }

    private static String boxed(ValueForm form) {
        return form instanceof Leaf leaf ? JavaNames.simple(JavaNames.boxed(leaf.typeName())) : form.sourceName();
    }

    /**
     * How deep the containers go: {@code 0} for a leaf, {@code 1} for {@code List<Duration>}, {@code 2} for
     * {@code Map<String, List<Point>>}. What a host compares its picker's cap against.
     */
    default int depth() {
        return switch (this) {
            case Leaf ignored -> 0;
            case Of of -> 1 + of.arguments().stream().mapToInt(ValueForm::depth).max().orElse(0);
            case Declared declared -> declared.arguments().stream().mapToInt(ValueForm::depth).max().orElse(0);
        };
    }

    /**
     * The leaf this form's own values are of, or {@code null} when it has none — the element of a list, the
     * value of a map, the form itself when it is a leaf.
     *
     * <p>The two questions asked of a leaf — <em>what may this value be</em> (the declared choices) and
     * <em>between which numbers</em> (the bounds) — are asked of the type the user types values of, which is
     * the last argument of a container and nothing at all for a container of containers.
     */
    default Leaf leaf() {
        return switch (this) {
            case Leaf leaf -> leaf;
            case Of of when of.last() instanceof Leaf leaf -> leaf;
            default -> null;
        };
    }
}
