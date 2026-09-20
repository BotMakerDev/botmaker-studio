package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.value.Range;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.ResolvedType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The editor's half of a stored value: what a fresh one starts as, how a value that arrived from anywhere is
 * reduced to something its type can hold, and how it is written into the user's own source.
 *
 * <h2>What this is not, since it replaced something larger</h2>
 *
 * <p>This is what is left of {@code VariableWire} after the vocabulary moved to the plugin contract
 * (plugin-platform phase 10b). That class held two things under one name: a {@code switch} over seventeen
 * hard-coded types saying what each one's text meant, and the shape-level rules that apply to all of them.
 * The first is gone — it was the host's copy of an answer only the plugin that owns the type can give, and
 * it is now {@link com.botmaker.plugin.api.value.ValueCodec}, asked through
 * {@link PluginHost#valueTypes()}. What stays here is the second, and the split is the repo's standing line:
 * <b>derivation belongs to the model, coercion belongs to the editor</b>. Clamping a number to a declared
 * range, pruning a value to the choices still on offer, ordering a multi-pick by its declaration — those are
 * rules a user can watch happen in a dialog, and they are the same rules whatever the type underneath is.
 *
 * <h2>Two spellings, and where each one stops</h2>
 *
 * <p>A row's value is the <b>Java initialiser</b> its field takes, because a composite has no other canonical
 * form. A <b>leaf</b> has a second text — the one its own control shows, and the one an option set is written
 * down in — so the join between the two lives at that one point ({@link #literal}, {@link #wire}) and a
 * composite is taken apart into parts that are themselves source ({@link #parts}, {@link #compose}).
 *
 * <p>Nothing composes a wire encoding of a composite, deliberately: that was what made a value of a type no
 * plugin installed today still readable, and it is what {@code docs/refactor/32-generic-values.md} decision 6
 * replaces with showing the author's own source, untouched.
 *
 * <h2>Why it lives beside the host's other halves of the contract (2026-09-11)</h2>
 *
 * <p>It was {@code project.activity.ValueWire} for as long as the host parsed {@code activities.json}
 * itself. That file is one plugin's and Studio no longer reads it — but this class never did: every type it
 * names is the contract's, every answer it gives comes from {@link PluginHost#valueTypes()}, and its callers
 * are the Parameters window, the Runner and the pickers, all of which work over rows a plugin hands over. So
 * it moved here, and the package it left is deleted.
 *
 * <h2>Every conversion is total</h2>
 *
 * <p>{@link #normalize} takes whatever the wire actually said and answers something this type can hold — a
 * garbage number, a choice that is no longer offered, a duration in a unit nobody knows. It cannot throw,
 * and it is a fixed point: normalising twice changes nothing.
 *
 * <p><b>A type nothing registered is the one thing left untouched.</b> Its text is kept exactly as the file
 * had it, because the host cannot canonicalise what it cannot read and rewriting it would destroy the value
 * of a variable whose plugin is merely absent. That state was unreachable while the vocabulary was a closed
 * enum; it is the ordinary state of a project opened without one of its plugins.
 */
public final class ValueWire {

    private ValueWire() {}

    private static ValueCatalog catalog() {
        return PluginHost.valueTypes();
    }

    /**
     * The registered type with this id, or an {@linkplain ValueType#unknown unknown} one.
     *
     * <p><b>An id written down in Studio's own source is not a back door</b>, and the distinction is worth
     * stating because it looks like one. The id is the word a {@code ParameterRow} carries; naming
     * {@code "YES_NO"} here is Studio spelling a type it draws a widget for. What it is not allowed to do is
     * reach past the host for the <em>answer</em> — hence
     * {@link PluginHost#valueTypes()} and never {@code SdkValueTypes} — so an id no plugin claims yields an
     * unknown type here rather than a compile error somewhere.
     */
    public static ValueType type(String id) {
        return catalog().type(id);
    }

    // ---- a row's value, which is Java source since 2026-09-20 --------------------------------------------
    //
    // ParameterRow.value() is the initialiser a field of the row's form takes (32-generic-values.md decision
    // 6), because a composite has no wire encoding at all. A *leaf* still has one — it is the text a control
    // shows — so the join between the two spellings is per item, and a composite is taken apart one level at
    // a time into parts that are themselves source. Nothing here composes a wire encoding of a composite.

    /**
     * A composite initialiser taken apart one level, each part with its own form — empty when nothing here
     * can read it.
     *
     * <p><b>Empty and "no parts" are different answers</b>, which is why this is an {@code Optional} rather
     * than a list: an empty list is a value a user chose, and a source this grammar did not write is a value
     * that must be shown as it stands and never replaced.
     */
    public static Optional<List<ValueCatalog.Part>> parts(ValueForm form, String source) {
        return catalog().partsOfInitializer(form, source);
    }

    /** The same, as the list a cell seeds its rows from — a source nothing can read seeds an empty one. */
    public static List<ValueCatalog.Part> partsOrNone(ValueForm form, String source) {
        return parts(form, source).orElse(List.of());
    }

    /** Parts, already written as source, composed back into the call this form's container spells. */
    public static String compose(ValueForm form, List<String> parts) {
        return catalog().initializerOfParts(form, parts).orElse("");
    }

    /** One leaf value as the Java a field of that type takes — {@code ""} when it has no spelling. */
    public static String literal(ValueType type, String wire) {
        return type == null ? ""
                : catalog().literal(type.id(), wire).map(ValueCatalog.Literal::source).orElse("");
    }

    /** The same read backwards: the text a leaf's own control shows — {@code ""} when it cannot read it. */
    public static String wire(ValueType type, String source) {
        return type == null ? "" : catalog().itemOfLiteral(type.id(), source).orElse("");
    }

    /**
     * The Java a freshly declared field of {@code form} is initialised with, or {@code ""} when there is
     * none — an unknown leaf, or a class the bot declares, for which no default may be invented.
     */
    public static String defaultInitializer(ValueForm form) {
        return catalog().defaultValue(form)
                .flatMap(value -> catalog().initializer(form, value))
                .orElse("");
    }

    // leafOf stood here from phase E until 2026-09-20, when the same question became ValueForm.leaf() in the
    // contract: every host asks it, it is answered out of the form alone, and one of the two callers it had
    // was a plugin's store. Callers say form.leaf().

    /** One free value of the type with this id — the form almost every caller wants. */
    public static ValueForm one(String id) {
        return ValueForm.of(type(id));
    }

    /** Every registered type, in the order the plugins registered them. */
    public static List<ValueType> registered() {
        return catalog().types();
    }

    /**
     * Every registered container, contract-seeded and contributed alike, in registration order.
     *
     * <p>No privilege for the three the contract seeds: a plugin's own {@code Set} or {@code Either} is
     * offered by the picker on the same terms, which is the whole point of a container being a contribution
     * rather than a constant.
     */
    public static List<ValueContainer<?>> containers() {
        return catalog().containers();
    }

    /**
     * The registered type a <em>Java</em> type name denotes — the inverse of {@link ValueType#sourceName()},
     * empty when no plugin claims it.
     *
     * <p>Matched on the <b>simple</b> name, so {@code Duration} and {@code java.time.Duration} both find the
     * duration type, exactly as the type name written in a bot's own source may be either. The one caller
     * reads a variable declaration out of the file rather than out of a dialog, and a declaration is free to
     * spell a type any way that compiles.
     */
    public static Optional<ValueType> bySourceName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String simple = name.substring(name.lastIndexOf('.') + 1).trim();
        return registered().stream()
                .filter(t -> t.sourceName().equals(simple) || t.boxedName().equals(simple))
                .findFirst();
    }

    // ---- what a form is, to the rest of the editor -----------------------------------------------------

    /**
     * The resolved type, so the expression menu can filter parameters against an expected slot type.
     *
     * <p>Was a {@code switch} over seventeen constants; it is three questions the form answers about itself
     * now — {@link ResolvedType#named} routes a primitive keyword to its own variant, so
     * {@code boolean}/{@code int}/{@code double}/{@code char} need no arm each. The one case a name cannot
     * carry is {@code String}: it is written unqualified and imports nothing, so nothing in the type says
     * {@code java.lang}.
     *
     * <p><b>A container answers the container</b>, never its element: what the menu asks is whether a
     * variable may be dropped into a slot, and {@code List<Duration>} fills a {@code List} slot rather than
     * a {@code Duration} one. A form the catalog has no container for answers its own written spelling,
     * which matches nothing and is the safe direction — an offer not made beats an offer that will not
     * compile.
     */
    public static ResolvedType resolvedType(ValueForm form) {
        if (form == null) return ResolvedType.named("");
        if (!(form instanceof ValueForm.Leaf leaf)) {
            return ResolvedType.named(form instanceof ValueForm.Of of
                    ? of.container().type().getName()
                    : form.sourceName());
        }
        ValueType base = leaf.type();
        if (!base.isPrimitive() && "String".equals(base.sourceName()) && base.importName().isEmpty()) {
            return ResolvedType.of(JdkType.STRING);
        }
        return ResolvedType.named(qualified(base));
    }

    /** True when a declared {@link Range} means anything for this type — the type's own answer. */
    public static boolean isBounded(ValueType type) {
        return type != null && type.bounded();
    }

    /**
     * The choices a type brings with it, for the ones whose option list is not the editor's to write: an enum
     * answers its own constants. Empty for everything else, whose choices come from the variable.
     */
    public static List<String> fixedOptions(ValueType type) {
        return type == null ? List.of() : type.options();
    }

    /** The choices actually in force: the type's own when it has any, else the editor's. */
    public static List<String> effectiveOptions(ValueType type, List<String> declared) {
        List<String> fixed = fixedOptions(type);
        if (!fixed.isEmpty()) return fixed;
        return declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
    }

    // ---- values ---------------------------------------------------------------------------------------

    // defaultWire, normalize and the choice-keyed normalizeOptions stood here until 2026-09-20 and went with
    // ValueChoice. What they did for a whole value is a plugin's job — ParameterStore coerces what it stores
    // — and what the editor needs of them is the one method below, asked of the leaf a cell types values of.

    /**
     * The declared choices as the leaf they are values of actually stores them: each normalised, duplicates
     * dropped, order kept.
     *
     * <p>Every choice is itself a value of that leaf, so it goes through the same normaliser the value does
     * — otherwise {@code "10 "} and {@code "10"} are two different choices, the radio button is labelled
     * with one and the stored value matches neither.
     */
    public static List<String> normalizeOptions(List<String> options, ValueType type, Range bounds) {
        if (options == null || type == null) return List.of();
        // The author's own list, never {@link #effectiveOptions}: an enum's constants are what its editor
        // offers to pick from, not a set to be copied onto every variable of that type and stored.
        return options.stream()
                .filter(Objects::nonNull)
                .map(option -> normalizeItem(option, type, bounds == null ? Range.NONE : bounds))
                .distinct()
                .toList();
    }

    /** {@code value} if it is still on offer, else the first thing that is. Unconstrained when nothing is. */
    private static String constrain(String value, List<String> choices) {
        if (choices.isEmpty()) return value;
        return choices.contains(value) ? value : choices.getFirst();
    }

    /**
     * One item, canonicalised by its own codec and then clamped to the declared range.
     *
     * <p>Clamping runs <em>after</em> the codec and is re-canonicalised afterwards, so the two cannot
     * disagree about the spelling of the result — a clamp that produced {@code "5"} for a decimal would
     * otherwise store text its own reader normalises to {@code "5.0"} on the very next open.
     */
    private static String normalizeItem(String wire, ValueType type, Range bounds) {
        String canonical = catalog().normalize(type.id(), wire);
        if (!type.bounded() || bounds.isEmpty()) return canonical;
        return catalog().normalize(type.id(), clamp(canonical, bounds));
    }

    private static String clamp(String canonical, Range bounds) {
        double value = parseDouble(canonical, 0.0);
        double min = parseDouble(bounds.min(), Double.NEGATIVE_INFINITY);
        double max = parseDouble(bounds.max(), Double.POSITIVE_INFINITY);
        double clamped = Math.max(min, Math.min(max, value));
        // Written back through the plain double spelling and read by the type's own codec, which is what
        // turns it into an int again for a whole number.
        return clamped == value ? canonical : Double.toString(clamped);
    }

    private static double parseDouble(String text, double fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            double parsed = Double.parseDouble(text.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- the user's own source ------------------------------------------------------------------------

    /**
     * The Java source of a single wire value, written out as a literal, with the one import it needs.
     *
     * <p>One caller, and it is not the generator: dropping a value into the <em>user's own</em> source from
     * the Variables screen. What a generated file gets is {@link ValueCatalog#initializer}, which composes
     * the shape and writes everything fully qualified so that no import can be forgotten; here an import is
     * arranged rather than avoided, because the user's file is one a person reads.
     *
     * @return the literal and the class to import ({@code null} when none is needed), or {@code null} for a
     *         type nothing registered — which has no written form at all, and must not be guessed one
     */
    public static Literal literalSource(ValueType type, String wire) {
        if (type == null) return null;
        return catalog().literal(type.id(), wire)
                .map(l -> new Literal(l.source(), l.importName().isEmpty() ? null : l.importName()))
                .orElse(null);
    }

    /** One literal and the import it needs — what {@code CodeEditor.replaceWithRawExpression} takes. */
    public record Literal(String source, String importFqn) {}

    // ---- naming ---------------------------------------------------------------------------------------

    private static String qualified(ValueType type) {
        return type.importName().isEmpty() ? type.sourceName() : type.importName();
    }

    private static String boxed(ValueType type) {
        return type.importName().isEmpty() ? type.boxedName() : type.importName();
    }
}
