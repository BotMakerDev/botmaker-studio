package com.botmaker.studio.plugin.grammar;

import java.util.Map;
import java.util.Set;

/**
 * How a Java type is spelled: its canonical name, its simple name, what a file imports to write it.
 *
 * <p>Two sets of answers, and the difference is whether a {@link Class} is in hand. A class a plugin handed
 * over knows exactly where its package ends; a name read out of a user's file does not, so the string
 * versions assume the Java convention that a package segment starts in lower case and a type in upper case.
 * That assumption is what every tool that reads Java without bindings makes, and a file that breaks it
 * reads as a type nobody declares — shown as written, never rewritten.
 *
 * <p>Reading a class's names never loads anything: the object is already the plugin's own.
 */
public final class JavaNames {

    private JavaNames() {}

    /** The primitive keywords, which are their own canonical names and import nothing. */
    static final Set<String> PRIMITIVES =
            Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double");

    /** What each primitive boxes to — the spelling a type argument needs. */
    private static final Map<String, String> BOXES = Map.of(
            "boolean", "java.lang.Boolean",
            "byte", "java.lang.Byte",
            "char", "java.lang.Character",
            "short", "java.lang.Short",
            "int", "java.lang.Integer",
            "long", "java.lang.Long",
            "float", "java.lang.Float",
            "double", "java.lang.Double");

    /**
     * {@code type}'s canonical name — {@code com.botmaker.sdk.api.flow.Flow.Activity} for a nested class,
     * where {@link Class#getName()} would say {@code Flow$Activity}. The binary name for a class that has no
     * canonical one (a local or anonymous class), which nothing writes and so nothing matches.
     */
    public static String canonical(Class<?> type) {
        if (type == null) return "";
        String canonical = type.getCanonicalName();
        return canonical != null ? canonical : type.getName();
    }

    /** {@code Flow.Activity} for {@code com.botmaker.sdk.api.flow.Flow.Activity}: the name past the package. */
    public static String simple(Class<?> type) {
        String canonical = canonical(type);
        String pkg = type == null ? "" : type.getPackageName();
        return pkg.isEmpty() || !canonical.startsWith(pkg + ".") ? canonical : canonical.substring(pkg.length() + 1);
    }

    /**
     * The class a file imports to write {@code type} by its simple name — the outermost class, since
     * importing it makes {@code Flow.Activity} writable — or {@code ""} for a primitive or anything in
     * {@code java.lang}, which needs none.
     */
    public static String importName(Class<?> type) {
        if (type == null || type.isPrimitive()) return "";
        Class<?> outer = type;
        while (outer.getEnclosingClass() != null) outer = outer.getEnclosingClass();
        if ("java.lang".equals(outer.getPackageName())) return "";
        return canonical(outer);
    }

    /**
     * The name past the package, for a name read out of a file: the first segment that starts in upper
     * case and everything after it. {@code Duration} stays {@code Duration}; a primitive stays itself.
     */
    public static String simple(String name) {
        if (name == null) return "";
        String trimmed = name.strip();
        int start = typeStart(trimmed);
        return start < 0 ? trimmed : trimmed.substring(start);
    }

    /**
     * What a file imports to write {@code name} by its simple name, for a name read out of a file — the
     * package plus the first type segment, or {@code ""} when there is no package to import from, the name
     * is a primitive, or it is in {@code java.lang}.
     */
    public static String importName(String name) {
        if (name == null) return "";
        String trimmed = name.strip();
        if (PRIMITIVES.contains(trimmed)) return "";
        int start = typeStart(trimmed);
        if (start <= 0) return "";
        if (trimmed.substring(0, start - 1).equals("java.lang")) return "";
        int end = trimmed.indexOf('.', start);
        return end < 0 ? trimmed : trimmed.substring(0, end);
    }

    /** The boxed spelling of a primitive keyword, or {@code name} itself. */
    public static String boxed(String name) {
        return name == null ? "" : BOXES.getOrDefault(name.strip(), name.strip());
    }

    /** Whether {@code name} is one of the eight primitive keywords. */
    public static boolean isPrimitive(String name) {
        return name != null && PRIMITIVES.contains(name.strip());
    }

    /** The index where the type part of a dotted name starts, or {@code -1} for a name with no package. */
    private static int typeStart(String name) {
        int segment = 0;
        while (segment < name.length()) {
            if (Character.isUpperCase(name.charAt(segment))) return segment;
            int dot = name.indexOf('.', segment);
            if (dot < 0) return -1;
            segment = dot + 1;
        }
        return -1;
    }
}
