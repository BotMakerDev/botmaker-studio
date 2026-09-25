package com.botmaker.studio.blocks.func;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * Whose code a call runs, which is what its block says before the method: the bot's own ("Call"), a plugin's
 * (the plugin's name — a facade the host recognises), the JDK's ("Java") or another jar's ("Library"). Every
 * call drew as "Call" or "🤖 SDK" before, so {@code Math.max}, a user's helper and a plugin's method read
 * alike, and a second plugin's call claimed to be the SDK's.
 */
public enum CallOwner {
    PROJECT("Call"),
    PLUGIN("Plugin"),
    JAVA("Java"),
    LIBRARY("Library");

    private final String label;

    CallOwner(String label) {
        this.label = label;
    }

    /** The word the block shows for this owner; a plugin call shows the plugin's own name instead. */
    public String label() {
        return label;
    }

    /**
     * Who declares {@code call}'s method, from its binding: source is the bot's, a class a loaded plugin
     * recognises is that plugin's, {@code java.*}, {@code javax.*} and {@code jdk.*} are the JDK's, anything
     * else a library's. No binding reads as the bot's — the block then says "call", which is what it always
     * said.
     */
    public static CallOwner of(IMethodBinding call) {
        ITypeBinding declaring = call == null ? null : call.getDeclaringClass();
        if (declaring == null) return PROJECT;
        declaring = declaring.getErasure();
        if (declaring.isFromSource()) return PROJECT;
        if (com.botmaker.studio.plugin.PluginHost.isFacadeClass(declaring.getName())) return PLUGIN;
        String pkg = declaring.getPackage() == null ? "" : declaring.getPackage().getName();
        if (pkg.equals("java") || pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jdk.")) {
            return JAVA;
        }
        return LIBRARY;
    }
}
