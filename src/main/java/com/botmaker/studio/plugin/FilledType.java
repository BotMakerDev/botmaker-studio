package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.palette.Fills;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.FilledTypes;

import java.lang.reflect.Method;

/**
 * The type the host declares for a plugin's {@link Fills} method: a value the bot fills in, whose fresh value is
 * a call to that method. Its only control is a pill saying what it is, with its Java in the tooltip; a UI draws
 * it from {@link #label} and {@link #help}, so this type draws nothing and needs none.
 *
 * <p>There is nothing here anyone configures: {@code Vision.lastMatch()} means <em>the match the bot found a
 * moment ago</em>, and any value typed in would be one the bot never sees. The pill names the type, never the
 * call, because it draws any value of the type the grammar could not read.
 */
public final class FilledType<T> implements PluginType<T> {

    private final Class<T> type;
    private final Method call;
    private final String label;

    private FilledType(Class<T> type, Method call, String label) {
        this.type = type;
        this.call = call;
        this.label = label;
    }

    static FilledType<?> of(FilledTypes.Filled filled) {
        return new FilledType<>(filled.type(), filled.call(), filled.label());
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public T fresh() {
        return null;
    }

    @Override
    public Method freshCall() {
        return call;
    }

    /** What the pill says: the type's name as the plugin gave it. */
    public String label() {
        return label;
    }

    /** The tooltip: the Java as written, when there is any, then what the bot does with it. */
    public static String help(String label, String source) {
        String explanation = label + ": the bot works this out while it runs, so there is nothing to set here.";
        return source == null || source.isBlank() ? explanation : source + "\n\n" + explanation;
    }

    @Override
    public String toString() {
        return "FilledType[" + type.getName() + "]";
    }
}
