package com.botmaker.studio.plugin;

import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.types.ResolvedType;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * The editor's structural half of a value: a composite taken apart one level and put back, and what a form
 * is to the rest of the editor.
 *
 * <p><b>What left on 2026-09-22</b> is everything that joined a value to <em>text</em>: {@code literal},
 * {@code wire}, {@code literalSource}, {@code defaultInitializer}, {@code normalizeOptions}, the clamp,
 * {@code bySourceName} and the id lookups. Each was a bridge between a row's Java and the stored text a
 * leaf's own control showed, and that text is gone — an editor is handed the value
 * ({@code ValueContext.value}) and writes one back ({@code ValueContext.set}), and the host's
 * {@link ValueGrammar} is the only thing that spells Java. What stays is what a <em>cell</em> needs to draw
 * a list or a map one row at a time.
 */
public final class ValueWire {

    private ValueWire() {}

    private static ValueGrammar grammar() {
        return PluginHost.grammar();
    }

    /**
     * A composite initialiser taken apart one level, each part with its own form — empty when nothing here
     * can read it.
     *
     * <p><b>Empty and "no parts" are different answers</b>: an empty list is a value a user chose, and a
     * source this grammar did not write is a value that must be shown as it stands and never replaced.
     */
    public static Optional<List<ValueGrammar.Part>> parts(Type form, String source) {
        return grammar().partsOfInitializer(form, source);
    }

    /** The same, as the list a cell seeds its rows from — a source nothing can read seeds an empty one. */
    public static List<ValueGrammar.Part> partsOrNone(Type form, String source) {
        return parts(form, source).orElse(List.of());
    }

    /** {@link #partsOrNone(Type, String)} one level further down: a part is taken apart as it was parsed. */
    public static List<ValueGrammar.Part> partsOrNone(Type form, SourceNode written) {
        return grammar().partsOfInitializer(form, written).orElse(List.of());
    }

    /** Parts, already written as source, composed back into the call this form's container spells. */
    public static String compose(Type form, List<String> parts) {
        return grammar().initializerOfParts(form, parts).orElse("");
    }

    /** The host's containers — what a picker offers to wrap a form in. */
    public static List<ValueContainer<?>> containers() {
        return grammar().containers();
    }

    /**
     * The resolved type, so the expression menu can filter parameters against an expected slot type.
     *
     * <p><b>A container answers the container</b>, never its element: {@code List<Duration>} fills a
     * {@code List} slot rather than a {@code Duration} one. A leaf the grammar cannot qualify answers its
     * written spelling, which matches nothing and is the safe direction — an offer not made beats an offer
     * that will not compile.
     */
    public static ResolvedType resolvedType(Type form) {
        return switch (form) {
            case ValueTypes.Parameterized parameterized -> ResolvedType.named(parameterized.raw().getName());
            case ValueTypes.BotClass bot -> ResolvedType.named(ValueTypes.sourceName(bot));
            case Class<?> cls when cls == String.class -> ResolvedType.of(JdkType.STRING);
            case Class<?> cls -> ResolvedType.named(JavaNames.canonical(cls));
            case null -> ResolvedType.named("");
            default -> ResolvedType.named(ValueTypes.sourceName(form));
        };
    }
}
