package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.TypeRef;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.managed.ManagedConstants;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link ValueContext} over one value the host is editing, with no call site behind it.
 *
 * <p>This is the half of "one editor, everywhere" that is not source code a plugin can point at: a row of
 * the Parameters window, and the expression a {@code @Managed} method returns. The value itself is Java in
 * all of them — {@code Duration.ofSeconds(3)} — so what distinguishes this from {@link HostSlotContext} is
 * only that there is no enclosing call to read or rewrite.
 *
 * <p><b>An editor is handed the value, never the text</b> (2026-09-22). {@link #value(Class)} reads the
 * seed expression through the {@link ValueGrammar} and {@link #set(Object)} writes one back through it, so
 * the plugin that owns the type never parses and never spells Java. {@link #source()} is only for showing an
 * expression the grammar could not read.
 *
 * <p><b>What is held is the value and its tree</b> (2026-09-24), not text. The seed is parsed once, here;
 * after a {@code set}, {@link #value} answers the value that was set and {@link #current()} the tree the
 * grammar built for it, which is what a sink copies into its file. Nothing re-parses a string this wrote.
 *
 * <p><b>The value is held here, not read back out of the widget.</b> An editor writes whenever the user
 * changes something, and the host reads {@link #current()} when it is time to store — the reason a plugin's
 * editor needs no lifecycle of its own.
 */
public final class HostValueContext implements ValueContext {

    private final TypeRef type;
    private final Type form;
    private final ValueGrammar grammar;
    private final StudioServices services;
    private final Consumer<JavaValue> onChange;
    private final Supplier<List<ManagedConstants.Constant>> constants;
    private final String seedText;
    private final SourceNode seed;
    /** The last value an editor set, and the tree it was written as; {@code null} before any write. */
    private Object held;
    private JavaValue written;

    public HostValueContext(TypeRef type, Type form, ValueGrammar grammar, String source,
                            StudioServices services, Consumer<JavaValue> onChange) {
        this(type, form, grammar, source, services, onChange, ConstantValues.NONE);
    }

    /**
     * @param constants the bot's {@code @Managed} constants, asked for when a value is read or written:
     *                  {@code Pictures.ORE} reads as the picture, and a picture equal to one is written as it
     */
    public HostValueContext(TypeRef type, Type form, ValueGrammar grammar, String source,
                            StudioServices services, Consumer<JavaValue> onChange,
                            Supplier<List<ManagedConstants.Constant>> constants) {
        this.type = type;
        this.form = form == null ? ValueTypes.NONE : form;
        this.grammar = grammar == null ? ValueGrammar.empty() : grammar;
        this.services = services;
        this.onChange = onChange;
        this.constants = constants == null ? ConstantValues.NONE : constants;
        this.seedText = source == null ? "" : source;
        this.seed = SourceNode.parse(seedText).orElse(null);
    }

    /** The context for a value of {@code form}, read and written through the bound plugins' grammar. */
    public static HostValueContext of(Type form, String source, StudioServices services,
                                      Consumer<JavaValue> onChange) {
        return of(form, PluginHost.grammar(), source, services, onChange, ConstantValues.NONE);
    }

    /** The same, reading and writing the bot's {@code @Managed} constants as the values they hold. */
    public static HostValueContext of(Type form, String source, StudioServices services,
                                      Consumer<JavaValue> onChange,
                                      Supplier<List<ManagedConstants.Constant>> constants) {
        return of(form, PluginHost.grammar(), source, services, onChange, constants);
    }

    /** The same, through {@code grammar} — the seam a test drives with the types it declares. */
    public static HostValueContext of(Type form, ValueGrammar grammar, String source,
                                      StudioServices services, Consumer<JavaValue> onChange) {
        return of(form, grammar, source, services, onChange, ConstantValues.NONE);
    }

    /** The same, through {@code grammar} and with {@code constants}. */
    public static HostValueContext of(Type form, ValueGrammar grammar, String source,
                                      StudioServices services, Consumer<JavaValue> onChange,
                                      Supplier<List<ManagedConstants.Constant>> constants) {
        Type safe = form == null ? ValueTypes.NONE : form;
        return new HostValueContext(typeRef(safe, grammar), safe, grammar, source, services, onChange,
                constants);
    }

    /**
     * A form as the Java type a plugin editor's predicate is written against: the class itself when the form
     * is one — a JDK literal, a declared type, a component — a bot's own class by its name, and an unknown
     * type as {@linkplain TypeRef#unresolved unresolved}, which no type-keyed editor claims.
     *
     * <p>A container answers the container's own class, so an editor claiming {@code java.util.List} is
     * offered the list and one claiming its element is not.
     */
    public static TypeRef typeRef(Type form, ValueGrammar grammar) {
        return switch (form) {
            case Class<?> cls -> TypeRef.of(cls);
            case ValueTypes.Parameterized parameterized -> TypeRef.of(parameterized.raw());
            case ValueTypes.BotClass bot -> HostTypes.named(bot.qualifiedName(),
                    bot.qualifiedName().substring(bot.qualifiedName().lastIndexOf('.') + 1));
            case ValueTypes.Unknown unknown -> TypeRef.unresolved(unknown.written());
            case null, default -> TypeRef.unresolved("");
        };
    }

    /**
     * The key a user's <i>Edit with</i> verdict is stored under for this value's type — the binary name the
     * canvas spells for the same type, so one verdict serves both; empty for a type that did not resolve.
     */
    public String typeName() {
        return switch (form) {
            case Class<?> cls -> cls.getName();
            case ValueTypes.Parameterized parameterized -> parameterized.raw().getName();
            case ValueTypes.BotClass bot -> bot.qualifiedName();
            case null, default -> "";
        };
    }

    /** The form this value is read and written as — the host's, never handed to a plugin. */
    public Type form() {
        return form;
    }

    @Override
    public TypeRef type() {
        return type;
    }

    @Override
    public <T> Optional<T> value(Class<T> type) {
        Optional<Object> value = written != null ? Optional.of(held)
                : seed == null ? Optional.empty() : ConstantValues.read(grammar, constants, form, seed);
        return value.flatMap(v -> ValueGrammar.as(v, type));
    }

    /**
     * Writes {@code value} through the grammar, or as the bot's constant holding it. A value the grammar
     * cannot write for this form is ignored rather than written half-way: there is no expression for it, and
     * the field keeps what it had.
     */
    @Override
    public void set(Object value) {
        ConstantValues.write(grammar, constants, form, value).ifPresent(tree -> {
            held = value;
            written = tree;
            if (onChange != null) onChange.accept(tree);
        });
    }

    @Override
    public String source() {
        return written != null ? written.source() : seedText;
    }

    /**
     * The value as it stands: the tree the last write built, or — before any write — the seed kept exactly as
     * written, so a value opened and closed with no edit reads back as it was. Empty for a seed that is not
     * one expression. What a host composing several values into one initialiser collects, where a single
     * value hands its write straight to {@code onChange}.
     */
    public Optional<JavaValue> current() {
        return written != null ? Optional.of(written) : Optional.ofNullable(seed).map(JavaValue::kept);
    }

    @Override
    public StudioServices services() {
        return services;
    }
}
