package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.TypeRef;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
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
 * held source through the {@link ValueGrammar} and {@link #set(Object)} writes one back through it, so the
 * plugin that owns the type never parses and never spells Java. {@link #source()} is only for showing an
 * expression the grammar could not read.
 *
 * <p><b>The value is held here, not read back out of the widget.</b> An editor writes whenever the user
 * changes something, and the host reads {@link #source()} when it is time to store — the reason a plugin's
 * editor needs no lifecycle of its own.
 */
public final class HostValueContext implements ValueContext {

    private final TypeRef type;
    private final ValueForm form;
    private final ValueGrammar grammar;
    private final StudioServices services;
    private final BiConsumer<String, List<String>> onChange;
    private final Supplier<List<ManagedConstants.Constant>> constants;
    private String source;
    private List<String> imports = List.of();

    public HostValueContext(TypeRef type, ValueForm form, ValueGrammar grammar, String source,
                            StudioServices services, BiConsumer<String, List<String>> onChange) {
        this(type, form, grammar, source, services, onChange, ConstantValues.NONE);
    }

    /**
     * @param constants the bot's {@code @Managed} constants, asked for when a value is read or written:
     *                  {@code Pictures.ORE} reads as the picture, and a picture equal to one is written as it
     */
    public HostValueContext(TypeRef type, ValueForm form, ValueGrammar grammar, String source,
                            StudioServices services, BiConsumer<String, List<String>> onChange,
                            Supplier<List<ManagedConstants.Constant>> constants) {
        this.type = type;
        this.form = form == null ? ValueForm.of("") : form;
        this.grammar = grammar == null ? ValueGrammar.empty() : grammar;
        this.services = services;
        this.onChange = onChange;
        this.constants = constants == null ? ConstantValues.NONE : constants;
        this.source = source == null ? "" : source;
    }

    /** The context for a value of {@code form}, read and written through the bound plugins' grammar. */
    public static HostValueContext of(ValueForm form, String source, StudioServices services,
                                      BiConsumer<String, List<String>> onChange) {
        return of(form, PluginHost.grammar(), source, services, onChange, ConstantValues.NONE);
    }

    /** The same, reading and writing the bot's {@code @Managed} constants as the values they hold. */
    public static HostValueContext of(ValueForm form, String source, StudioServices services,
                                      BiConsumer<String, List<String>> onChange,
                                      Supplier<List<ManagedConstants.Constant>> constants) {
        return of(form, PluginHost.grammar(), source, services, onChange, constants);
    }

    /** The same, through {@code grammar} — the seam a test drives with the types it declares. */
    public static HostValueContext of(ValueForm form, ValueGrammar grammar, String source,
                                      StudioServices services, BiConsumer<String, List<String>> onChange) {
        return of(form, grammar, source, services, onChange, ConstantValues.NONE);
    }

    /** The same, through {@code grammar} and with {@code constants}. */
    public static HostValueContext of(ValueForm form, ValueGrammar grammar, String source,
                                      StudioServices services, BiConsumer<String, List<String>> onChange,
                                      Supplier<List<ManagedConstants.Constant>> constants) {
        ValueForm safe = form == null ? ValueForm.of("") : form;
        return new HostValueContext(typeRef(safe, grammar), safe, grammar, source, services, onChange,
                constants);
    }

    /**
     * A form as the Java type a plugin editor's predicate is written against: the leaf's canonical name
     * when the grammar knows it, and the name as written otherwise — which {@link TypeRef} models as
     * unresolved, and which an {@code isNamed} predicate still matches.
     *
     * <p>A container answers the container's own name, so an editor claiming {@code java.util.List} is
     * offered the list and one claiming its element is not.
     */
    public static TypeRef typeRef(ValueForm form, ValueGrammar grammar) {
        String qualified;
        String simple;
        switch (form) {
            case ValueForm.Leaf leaf -> {
                String resolved = grammar == null ? null : grammar.qualify(leaf.typeName());
                qualified = resolved != null ? resolved : leaf.typeName().indexOf('.') >= 0 ? leaf.typeName() : "";
                simple = JavaNames.simple(resolved != null ? resolved : leaf.typeName());
            }
            case ValueForm.Of of -> {
                qualified = of.container().sourceName();
                simple = of.container().type().getSimpleName();
            }
            case ValueForm.Declared declared -> {
                qualified = declared.qualifiedName();
                simple = JavaNames.simple(declared.qualifiedName());
            }
            case null -> {
                qualified = "";
                simple = "";
            }
        }
        String q = qualified;
        String s = simple;
        return new TypeRef() {
            @Override public String simpleName() { return s; }

            @Override public String qualifiedName() { return q; }
        };
    }

    /** The form this value is read and written as — the host's, never handed to a plugin. */
    public ValueForm form() {
        return form;
    }

    @Override
    public TypeRef type() {
        return type;
    }

    @Override
    public <T> Optional<T> value(Class<T> type) {
        return ConstantValues.read(grammar, constants, form, source).flatMap(value -> ValueGrammar.as(value, type));
    }

    /**
     * Writes {@code value} through the grammar, or as the bot's constant holding it. A value the grammar
     * cannot spell for this form is ignored rather than written half-way: there is no expression for it, and
     * the field keeps what it had.
     */
    @Override
    public void set(Object value) {
        ConstantValues.write(grammar, constants, form, value)
                .ifPresent(written -> change(written.source(), written.imports()));
    }

    @Override
    public String source() {
        return source;
    }

    private void change(String newSource, List<String> newImports) {
        source = newSource;
        imports = newImports == null ? List.of() : List.copyOf(newImports);
        if (onChange != null) onChange.accept(source, imports);
    }

    /**
     * The imports the last write said its Java needs — empty before any write, since the source the context
     * was seeded with is already in the file with whatever it imports. What a host composing several values
     * into one initialiser collects, where a single value hands them straight to {@code onChange}.
     */
    public List<String> imports() {
        return imports;
    }

    @Override
    public StudioServices services() {
        return services;
    }
}
