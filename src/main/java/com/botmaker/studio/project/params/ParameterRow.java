package com.botmaker.studio.project.params;

import java.util.List;
import java.util.Objects;

/**
 * One row of the Parameters window — one parameter, as everything that draws or edits one reads it.
 *
 * <p><b>What a row comes from is the bot's own Java.</b> A parameter is a {@code @Param} static field the
 * host reads off the syntax tree, and the section it is listed under is the class that declares it. A
 * plugin that wants a row of its own puts a {@code @Param} field in the file it ships, and the same walk
 * finds it.
 *
 * <p><b>Studio's since 2026-09-28.</b> It sat in the contract's {@code parameters} package after the surface
 * that let a plugin hand rows over was deleted (2026-09-22), and no plugin ever read or built one again.
 *
 * <p><b>The type is the written name, not a tree</b> (2026-09-22). It carried a {@code ValueForm} until the
 * grammar became the host's — a {@code Map<String, List<Duration>>} is a structure only the thing that
 * writes and reads the Java needs to walk, and nothing outside the host ever walked one. What a row's
 * consumers actually use is the spelling: to label the row, to compare two rows, and to find the type's
 * editor. That is {@link #typeName()}.
 *
 * <h2>The value is one source string, because a composite has no other canonical form</h2>
 *
 * <p>It was a {@code List<String>} of wires until 2026-09-20 — one entry for an ordinary row, one per item
 * for a list-shaped one — and that list was only ever there to carry the <em>list</em> shape. It could not
 * encode {@code Map<String, List<Duration>>}, which a bot may perfectly well declare, so the value crosses
 * as <b>the Java initialiser the field takes</b>. {@code 32-generic-values.md} decision 6.
 *
 * <p>That is not a wire encoding in a different spelling. A wire existed so a <em>bot</em> could read a
 * value with the contract jar absent; nothing at runtime reads a value once the model is compiled code. The
 * source is the artifact, and a value's canonical form is the source that produces it.
 *
 * <h2>No section here, deliberately</h2>
 *
 * <p>A row does not carry the class it was read out of. Nothing that holds a row holds it alone — the host
 * pairs it with the field it came from, which knows the file, the class and whether the value may be
 * rewritten — so a section component here could only ever repeat that or contradict it.
 */
public final class ParameterRow {

    /** The heading a row with no category is listed under. Not a real category: nothing declares it. */
    public static final String GENERAL = "General";

    private final String name;
    private final String typeName;
    private final String value;
    private final String description;
    private final String category;
    private final Visibility visibility;
    private final List<String> options;
    private final double min;
    private final double max;

    private ParameterRow(Builder b) {
        this.name = b.name;
        this.typeName = b.typeName;
        this.value = b.value == null ? "" : b.value;
        this.description = b.description == null ? "" : b.description;
        this.category = b.category == null ? "" : b.category.trim();
        this.visibility = b.visibility == null ? Visibility.PUBLIC : b.visibility;
        this.options = List.copyOf(b.options);
        this.min = b.min;
        this.max = b.max;
    }

    /**
     * A builder for the row {@code name} names, holding a value written as {@code typeName}.
     *
     * @param name     the field name the declaring class carries for this value, and the name a bot writes
     *                 down to read it — a valid Java identifier, unique within its section
     * @param typeName the declared type exactly as the field writes it: {@code int},
     *                 {@code java.time.Duration}, {@code Map<String, List<Point>>}
     */
    public static Builder named(String name, String typeName) {
        return new Builder(name, typeName);
    }

    /** The field name, unique within its group. Never blank — the builder refuses one. */
    public String name() {
        return name;
    }

    /** The declared type exactly as the field writes it. Never {@code null}; blank when the host had none. */
    public String typeName() {
        return typeName;
    }

    /**
     * The value, as the Java initialiser a field of this {@link #typeName()} takes. Never {@code null};
     * blank when there is none.
     */
    public String value() {
        return value;
    }

    /** A sentence explaining what the value is for, in the user's words. May be empty. */
    public String description() {
        return description;
    }

    /**
     * The category this row is filed under — free text, written by whoever declared the parameter and
     * compared case-insensitively by the window's rail. Blank means uncategorised, the ordinary case.
     */
    public String category() {
        return category;
    }

    /** The category this is filed under, or {@link #GENERAL} when it carries none. */
    public String categoryOrGeneral() {
        return category.isBlank() ? GENERAL : category;
    }

    /** Whether whoever runs the bot is offered this row at all. */
    public Visibility visibility() {
        return visibility;
    }

    /** True when whoever runs the bot is offered this row. */
    public boolean isPublic() {
        return visibility == Visibility.PUBLIC;
    }

    /**
     * The set of values this row may take, each as the Java expression that writes one. Empty for a free
     * value: whether a set is declared is a question about the declaration rather than about the type,
     * which is why the type does not answer it.
     */
    public List<String> options() {
        return options;
    }

    /**
     * The declared inclusive lower bound, or {@link Double#NEGATIVE_INFINITY} when none is declared.
     *
     * <p><b>A number, not text</b> (2026-09-22). Both ends were strings so a duration bound could be
     * written the way a duration is ({@code "30s"}) and a codec would parse it — and no plugin parses
     * anything now. Every bounded type is a number or is measured by one, and both ends are advice to a
     * widget and a clamp when a value is normalised, never a validation that can fail: a value outside the
     * range is pulled to the nearest bound, because the alternative is a project that refuses to save
     * because of a limit somebody tightened after the fact.
     *
     * <p>Independent ends are the point. "At most 10" is a sentence a person says, and it was once
     * unsayable because the widget only appeared when both ends were filled in.
     */
    public double min() {
        return min;
    }

    /** The declared inclusive upper bound, or {@link Double#POSITIVE_INFINITY} when none is declared. */
    public double max() {
        return max;
    }

    /** Whether either end is declared — what a widget asks before offering a slider. */
    public boolean isBounded() {
        return min != Double.NEGATIVE_INFINITY || max != Double.POSITIVE_INFINITY;
    }

    /** What an editor calls this — its {@link #description()} when it has one, else its {@link #name()}. */
    public String displayLabel() {
        return description.isBlank() ? name : description;
    }

    /**
     * This row with another value and everything else unchanged — what a plugin answers an edit with once it
     * has stored, and possibly normalised, what the host sent.
     */
    public ParameterRow withValue(String newValue) {
        return toBuilder().value(newValue).build();
    }

    /** A builder holding everything this row holds — the way to change more than one thing at a time. */
    public Builder toBuilder() {
        return new Builder(name, typeName)
                .value(value)
                .description(description)
                .category(category)
                .visibility(visibility)
                .options(options)
                .bounds(min, max);
    }

    /** Equal when every component is: what makes "did anything change" answerable. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParameterRow other)) return false;
        return name.equals(other.name)
                && typeName.equals(other.typeName)
                && value.equals(other.value)
                && description.equals(other.description)
                && category.equals(other.category)
                && visibility == other.visibility
                && options.equals(other.options)
                && Double.compare(min, other.min) == 0
                && Double.compare(max, other.max) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typeName, value, description, category, visibility, options, min, max);
    }

    @Override
    public String toString() {
        return "ParameterRow[" + name + " : " + typeName + " = " + value + "]";
    }

    /** Collects a row. Every setter is optional; {@link #named(String, String)} carries the two that are not. */
    public static final class Builder {

        private final String name;
        private final String typeName;
        private String value = "";
        private String description;
        private String category;
        private Visibility visibility;
        private List<String> options = List.of();
        private double min = Double.NEGATIVE_INFINITY;
        private double max = Double.POSITIVE_INFINITY;

        private Builder(String name, String typeName) {
            this.name = name == null ? "" : name.trim();
            if (this.name.isEmpty()) throw new IllegalArgumentException("a parameter row needs a name");
            this.typeName = typeName == null ? "" : typeName.trim();
        }

        /** The value, as the Java initialiser a field of this type takes. {@code null} is no value. */
        public Builder value(String value) {
            this.value = value == null ? "" : value;
            return this;
        }

        /** The sentence a user reads instead of the field name. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** The category inside the section, out of the owning group's declared set. */
        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /** Whether the bot's user is offered this. Defaults to {@link Visibility#PUBLIC} — a parameter exists to be set. */
        public Builder visibility(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        /** The declared set of values, for a shape that has one. */
        public Builder options(List<String> options) {
            this.options = options == null ? List.of() : List.copyOf(options);
            return this;
        }

        /**
         * The declared inclusive bounds. Pass {@link Double#NEGATIVE_INFINITY} or
         * {@link Double#POSITIVE_INFINITY} for an end that is not declared — the two are independent.
         */
        public Builder bounds(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public ParameterRow build() {
            return new ParameterRow(this);
        }
    }
}
