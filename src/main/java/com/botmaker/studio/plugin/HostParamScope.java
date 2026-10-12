package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.ParamScope;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.studio.project.params.JavaParameter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open bot as a conditional parameter's condition reads it ({@code ManagedValue.Reason.when}): each of the
 * plugin's values as the bot's Java holds it, through {@link PluginValues#open}. And the one question the host asks
 * with it — whether a parameter applies — before writing it into a bot or listing it.
 *
 * <p>One scope stands for one look at the bot: a value is read once and kept, so a window listing many conditional
 * parameters reads each value they ask about once rather than once per row. Make a new one per reload.
 */
public final class HostParamScope implements ParamScope {

    private final PluginValues values;
    private final Map<String, Optional<?>> read = new HashMap<>();

    private HostParamScope(PluginValues values) {
        this.values = values == null ? PluginValues.NONE : values;
    }

    /** A scope over {@code values}, the open project's, as they stand now. */
    public static HostParamScope over(PluginValues values) {
        return new HostParamScope(values);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> Optional<V> value(ManagedValue<V> value) {
        if (value == null || value.type() == null) return Optional.empty();
        return (Optional<V>) read.computeIfAbsent(value.id(), id -> {
            try {
                return values.open(value).flatMap(ctx -> ctx.value(value.type()));
            } catch (RuntimeException | LinkageError unreadable) {
                return Optional.empty();
            }
        });
    }

    /**
     * Whether {@code parameter} is listed: the bot's own always, a plugin's while its declaration applies to the bot
     * this scope reads. A mark no loaded plugin declares is listed, as it always was.
     */
    public boolean shown(JavaParameter parameter) {
        return parameter == null || declaration(parameter.managedId(), PluginHost.managedValues())
                .map(declared -> declared.appliesIn(this)).orElse(true);
    }

    /** The parameter {@code id} names among {@code known}. */
    static Optional<ManagedValue<?>> declaration(String id, List<ManagedValue<?>> known) {
        if (id == null || id.isEmpty() || known == null) return Optional.empty();
        return known.stream().filter(value -> value != null && value.isParam() && id.equals(value.id())).findFirst();
    }
}
