package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.BotRecords;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every type the chooser offers, grouped: <b>Java</b> first, then <b>This project</b>, then one group per
 * plugin by its name — each group alphabetical (2026-09-26). No JavaFX, so the rules are tested headlessly.
 *
 * <p><b>A row is named as Java names it</b> — {@code int}, {@code Point} — with the plain words as a hint
 * beside it. "Whole number" for {@code int} was a second vocabulary for a word the user then read in the code
 * view, the Errors tab and every stack trace.
 *
 * <p><b>One catalog for every place a type is chosen</b>: a parameter, a local variable, a function's inputs and
 * its result. What differs is {@link Purpose}, which only narrows the list.
 */
public final class TypeCatalog {

    /** What the type is being chosen for — the only thing that differs between the places a type is chosen. */
    public enum Purpose {
        /**
         * A value somebody edits in the Parameters window: only a type a picker can write — one a plugin
         * declares — or a record the bot declares.
         */
        VALUE,
        /** A variable or a function input: anything Java can declare, so every class the bot declares too. */
        DECLARATION,
        /** A function's result: a declaration's list plus {@code void}. */
        RETURN;

        boolean offersVoid() {
            return this == RETURN;
        }
    }

    /** One offered type: its Java name, a few plain words, and the type itself. */
    public record Entry(String label, String hint, Type type) {}

    /** A heading and its entries, alphabetical. */
    public record Group(String title, List<Entry> entries) {}

    public static final String JAVA = "Java";
    public static final String PROJECT = "This project";

    /** The Java group, in the words a row shows beside each name. Declaration order is irrelevant: it sorts. */
    private static final Map<Class<?>, String> JAVA_TYPES = javaTypes();

    private final List<Group> groups;

    private TypeCatalog(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    /** The catalog of the project bound right now: the loaded plugins' types and {@code project}'s classes. */
    public static TypeCatalog current(Purpose purpose, BotRecords project) {
        return of(purpose, PluginHost.grammar(), PluginHost.ownedTypes(),
                project == null ? List.of() : project.shapes());
    }

    /** The same from explicit parts — the seam a test uses. */
    public static TypeCatalog of(Purpose purpose, ValueGrammar grammar, List<PluginHost.OwnedType> plugins,
                                 Collection<BotRecords.Shape> project) {
        List<Group> out = new ArrayList<>();
        Set<Class<?>> listed = new HashSet<>();

        List<Entry> java = new ArrayList<>();
        JAVA_TYPES.forEach((cls, hint) -> {
            if (cls == void.class && !purpose.offersVoid()) return;
            // The grammar reads every JDK literal, but only a declared type has a picker to edit it with.
            if (purpose == Purpose.VALUE && grammar.type(cls).isEmpty()) return;
            java.add(new Entry(JavaNames.simple(cls), hint, cls));
            listed.add(cls);
            listed.add(boxed(cls));
        });
        add(out, JAVA, java);

        List<Entry> own = new ArrayList<>();
        for (BotRecords.Shape shape : project) {
            if (purpose == Purpose.VALUE && !shape.isRecord()) continue;
            own.add(new Entry(shape.simpleName(), shape.isRecord() ? "record" : "class",
                    new ValueTypes.BotClass(shape.qualifiedName(), List.of())));
        }
        add(out, PROJECT, own);

        Map<String, List<Entry>> byPlugin = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (PluginHost.OwnedType owned : plugins) {
            Class<?> cls = owned.type();
            if (!listed.add(cls)) continue;
            names.putIfAbsent(owned.pluginId(), owned.pluginName());
            byPlugin.computeIfAbsent(owned.pluginId(), id -> new ArrayList<>())
                    .add(new Entry(JavaNames.simple(cls), packageOf(cls), cls));
        }
        names.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> add(out, e.getValue(), byPlugin.get(e.getKey())));
        return new TypeCatalog(out);
    }

    public List<Group> groups() {
        return groups;
    }

    /**
     * The groups holding an entry whose name or hint contains {@code query}, ignoring case; a group with no
     * match is left out. A blank query is the whole catalog.
     */
    public List<Group> filter(String query) {
        if (query == null || query.isBlank()) return groups;
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<Group> out = new ArrayList<>();
        for (Group group : groups) {
            List<Entry> hits = group.entries().stream()
                    .filter(e -> e.label().toLowerCase(Locale.ROOT).contains(q)
                            || e.hint().toLowerCase(Locale.ROOT).contains(q))
                    .toList();
            if (!hits.isEmpty()) out.add(new Group(group.title(), hits));
        }
        return out;
    }

    private static void add(List<Group> out, String title, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        List<Entry> sorted = entries.stream()
                .sorted(Comparator.comparing(Entry::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
        out.add(new Group(title, sorted));
    }

    private static String packageOf(Class<?> cls) {
        Package p = cls.getPackage();
        return p == null ? "" : p.getName();
    }

    private static Class<?> boxed(Class<?> cls) {
        return cls.isPrimitive() && cls != void.class
                ? java.lang.invoke.MethodType.methodType(cls).wrap().returnType() : cls;
    }

    private static Map<Class<?>, String> javaTypes() {
        Map<Class<?>, String> m = new LinkedHashMap<>();
        m.put(boolean.class, "true or false");
        m.put(char.class, "one character");
        m.put(int.class, "whole number");
        m.put(long.class, "large whole number");
        m.put(double.class, "decimal number");
        m.put(String.class, "text");
        m.put(void.class, "nothing — the function gives no result");
        return m;
    }
}
