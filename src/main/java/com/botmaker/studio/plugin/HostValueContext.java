package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.TypeRef;
import com.botmaker.plugin.api.ValueContext;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * A {@link ValueContext} over one value the host is editing, with no call site behind it.
 *
 * <p>This is the half of "one editor, everywhere" that is not source code a plugin can point at: a row of
 * the Parameters window, and the expression a {@code @Managed} method returns. The value itself is Java in
 * all of them — {@code Duration.ofSeconds(3)} — since 2026-09-20, so what distinguishes this from
 * {@link HostSlotContext} is only that there is no enclosing call to read or rewrite.
 *
 * <p>An editor is chosen by the <b>Java</b> type the value has, which is what a slot in a bot's source has
 * too. {@link ValueType#sourceName()} and {@link ValueType#importName()} are the bridge: a plugin that
 * claims {@code com.acme.Channel} therefore matches in both places, having written the predicate once.
 *
 * <p><b>The value is held here, not read back out of the widget.</b> An editor writes through {@link #set}
 * whenever the user changes something, and the host reads {@link #source()} when it is time to store — the
 * same discipline the built-in editors follow with their {@code read} suppliers, and the reason a plugin's
 * editor needs no lifecycle of its own.
 */
public final class HostValueContext implements ValueContext {

    private final TypeRef type;
    private final ValueForm form;
    private final StudioServices services;
    private final BiConsumer<String, List<String>> onChange;
    private String source;

    public HostValueContext(TypeRef type, ValueForm form, String source, StudioServices services,
                            BiConsumer<String, List<String>> onChange) {
        this.type = type;
        this.form = form == null ? ValueForm.of(ValueType.unknown("")) : form;
        this.services = services;
        this.onChange = onChange;
        this.source = source == null ? "" : source;
    }

    /**
     * The context for a value of {@code form} — the vocabulary form translated into the Java type a plugin
     * editor's predicate is written against.
     */
    public static HostValueContext of(ValueForm form, String source, StudioServices services,
                                      BiConsumer<String, List<String>> onChange) {
        ValueForm safe = form == null ? ValueForm.of(ValueType.unknown("")) : form;
        return new HostValueContext(typeRef(safe.leaf()), safe, source, services, onChange);
    }

    /**
     * A {@link ValueType} as the Java type it generates.
     *
     * <p>{@link ValueType#importName()} is the fully-qualified name and is blank for a primitive or for a
     * type nothing registered, in which case the simple name is all there is — which {@link TypeRef} already
     * models as "unresolved", and which a plugin predicate written with {@code isNamed} still matches.
     */
    public static TypeRef typeRef(ValueType type) {
        String simple = type == null ? "" : type.sourceName();
        String qualified = type == null ? "" : type.importName();
        return new TypeRef() {
            @Override public String simpleName() { return simple == null ? "" : simple; }

            @Override public String qualifiedName() { return qualified == null ? "" : qualified; }
        };
    }

    @Override
    public TypeRef type() {
        return type;
    }

    @Override
    public ValueForm form() {
        return form;
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public void set(String javaExpression, String... importsNeeded) {
        source = javaExpression == null ? "" : javaExpression;
        if (onChange != null) {
            onChange.accept(source, importsNeeded == null ? List.of() : List.of(importsNeeded));
        }
    }

    @Override
    public StudioServices services() {
        return services;
    }
}
