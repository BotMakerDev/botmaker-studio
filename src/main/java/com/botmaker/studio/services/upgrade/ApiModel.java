package com.botmaker.studio.services.upgrade;

import com.botmaker.shared.github.SemVer;
import com.botmaker.studio.index.LibraryType;
import com.botmaker.studio.index.TypeSummaryManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One plugin jar, reduced to what an upgrade tells the user about it: the public classes it declares, their
 * members, and the release each arrived in — the "What's new" a report leads with.
 *
 * <p>Everything is read from the <b>class file</b> rather than by reflection: the jar being read is on no
 * classpath, and may be a version of the plugin this Studio has never run against.
 *
 * <p><b>It no longer decides what breaks (2026-10-16).</b> Until then it also read each element's
 * {@code @ReplacedBy}, and the upgrade diffed two snapshots into breaks and redirects by simple name and arity.
 * The bot's own {@code @Migrate} rules replaced the pointers, and what a move breaks is now javac's answer over
 * the bot against both classpaths ({@code migrate.Migration}).
 */
final class ApiModel {

    /** A constructor has no name of its own; this is how a member map spells one. */
    static final String CTOR = "<init>";

    private ApiModel() {
    }

    /**
     * One public class: its members by name, and the release it first appeared in, {@code ""} when it does not
     * say.
     */
    record ApiClass(String name, String simpleName, String since, Map<String, List<ApiMember>> byName) {

        /** Whether this class offers {@code name} as something callable: a method or a constructor. */
        boolean declaresCallable(String name) {
            return byName.getOrDefault(name, List.of()).stream().anyMatch(m -> !m.field());
        }
    }

    /** One public member: a method, a constructor or a field, and the release it arrived in. */
    record ApiMember(String name, boolean field, String since) {}

    /**
     * Scans one library jar down to every public class it declares. Goes through {@link TypeSummaryManager} so
     * the scan lands in the same per-jar disk cache everything else uses.
     *
     * <p><b>{@link TypeSummaryManager#overEverything()}, deliberately, and not the default manager</b>: the
     * default filters to the packages the currently bound plugins catalogue, and the jar read here may belong to
     * a plugin that is not bound. What a jar contains is a property of the jar.
     */
    static Map<String, ApiClass> snapshot(Path jar) {
        TypeSummaryManager index = TypeSummaryManager.overEverything();
        index.refresh(List.of(jar.toString()));
        Map<String, ApiClass> out = new LinkedHashMap<>();
        for (LibraryType type : index.getAllTypes()) {
            out.put(type.simpleName(), apiClassOf(type));
        }
        return out;
    }

    static ApiClass apiClassOf(LibraryType type) {
        Map<String, List<ApiMember>> byName = new LinkedHashMap<>();
        addMethods(byName, type.methods(), false);
        addMethods(byName, type.constructors(), true);
        // An enum constant is a public static field of its enum, so it needs no case of its own.
        for (LibraryType.Field field : type.fields()) {
            if (!field.isPublic() || field.isSynthetic()) continue;
            byName.computeIfAbsent(field.name(), k -> new ArrayList<>())
                    .add(new ApiMember(field.name(), true, field.since()));
        }
        return new ApiClass(type.name(), type.simpleName(), type.since(), Map.copyOf(byName));
    }

    private static void addMethods(Map<String, List<ApiMember>> byName, List<LibraryType.Method> methods,
                                   boolean constructors) {
        for (LibraryType.Method method : methods) {
            if (!method.isPublic() || method.isSynthetic()) continue;
            String name = constructors ? CTOR : method.name();
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(new ApiMember(name, false, method.since()));
        }
    }

    /**
     * New classes, and new members on classes that already existed — display text, sorted, and <b>grouped by
     * the release each arrived in</b>.
     *
     * <p>The era comes from {@code @Since}, asked of the element itself and falling back to its declaring
     * class. Everything that answers nothing lands in the {@code ""} bucket, sorted last and rendered without a
     * heading. {@link #offerable} drops the package a plugin keeps its implementation in: this is the one list
     * that shows a name the bot never wrote.
     */
    static Map<String, List<String>> additions(Map<String, ApiClass> before, Map<String, ApiClass> after) {
        Map<String, Set<String>> byEra = new LinkedHashMap<>();
        for (ApiClass now : after.values()) {
            if (!offerable(now)) continue;
            ApiClass then = before.get(now.simpleName());
            if (then == null) {
                byEra.computeIfAbsent(now.since(), k -> new TreeSet<>())
                        .add(now.simpleName() + " (new class)");
                continue;
            }
            for (Map.Entry<String, List<ApiMember>> entry : now.byName().entrySet()) {
                String name = entry.getKey();
                if (then.byName().containsKey(name)) continue;
                // A constant is read as a value, so it is shown as one: Key.ENTER, not Key.ENTER(…).
                String call = now.declaresCallable(name) ? "(…)" : "";
                String era = entry.getValue().stream().map(ApiMember::since)
                        .filter(s -> !s.isBlank()).findFirst().orElse(now.since());
                byEra.computeIfAbsent(era, k -> new TreeSet<>())
                        .add(CTOR.equals(name)
                                ? "new " + now.simpleName() + "(…)"
                                : now.simpleName() + "." + name + call);
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byEra.entrySet().stream()
                // Newest first, and the era nobody declared last.
                .sorted((a, b) -> a.getKey().isBlank() ? 1 : b.getKey().isBlank() ? -1
                        : compareVersions(strip(b.getKey()), strip(a.getKey())))
                .forEach(e -> out.put(e.getKey(), List.copyOf(e.getValue())));
        // Not Map.copyOf: that is a hash map, and the sort immediately above is the whole point.
        return Collections.unmodifiableMap(out);
    }

    /**
     * Whether a class is worth announcing to somebody deciding whether to take this release: not in an
     * {@code internal} package, the boundary {@code com.botmaker.sdk.api} / {@code com.botmaker.sdk.internal}
     * states and {@code botmaker-plugin-archetype} generates. A heuristic, confined to a display list.
     */
    private static boolean offerable(ApiClass klass) {
        String pkg = klass.name().substring(0, Math.max(0, klass.name().lastIndexOf('.')));
        return !pkg.equals("internal") && !pkg.startsWith("internal.")
                && !pkg.endsWith(".internal") && !pkg.contains(".internal.");
    }

    /** Ascending semver where both sides parse, falling back to text so the sort stays total. */
    static int compareVersions(String a, String b) {
        if (SemVer.isValid(a) && SemVer.isValid(b)) return SemVer.compare(a, b);
        return a.compareTo(b);
    }

    /** Release tags are cut as {@code v1.0.26}; {@code SemVer} wants {@code 1.0.26}. */
    static String strip(String version) {
        if (version == null) return "";
        String t = version.trim();
        return (t.startsWith("v") || t.startsWith("V")) ? t.substring(1) : t;
    }
}
