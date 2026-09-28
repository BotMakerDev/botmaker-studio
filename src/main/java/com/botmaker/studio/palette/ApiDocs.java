package com.botmaker.studio.palette;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory API documentation of the bound plugins: {@code class → method → [overloads]}, each overload
 * carrying a Javadoc summary and its ordered parameters (real names + {@code @param} text).
 *
 * <p>Javadoc is not in bytecode, so the docs come from the plugins the bot resolves: {@code index/ApiDocsParser}
 * parses each plugin's {@code sources} jar at runtime (via Eclipse JDT) into an instance of this class, and
 * {@code services/ApiDocsService} merges and caches them per project. It was {@code SdkDocs}, over the SDK's
 * jar alone, until 2026-09-28. This type is pure data +
 * lookup — no I/O — so it stays in the dependency-light {@code palette} package. {@link #EMPTY} is the
 * no-docs fallback (sources not resolved / offline).
 */
public final class ApiDocs {

    /** One parameter of a documented method overload: its real name, declared type, and {@code @param} text. */
    public record Param(String name, String type, String desc) {}

    /**
     * One method overload: the Javadoc summary, its ordered parameters, and — when the method is on its way
     * out — the {@code @deprecated} tag's text.
     *
     * <p>{@code deprecated} is the <em>explanation</em> ("use {@code startIfNotRunning()} instead"), never the
     * fact. The fact comes from the {@code @Deprecated} annotation in bytecode, via
     * {@code ProjectAnalyzer.isMemberDeprecated}; that split is deliberate, because the two can disagree and only one
     * of them is what the compiler will act on. A method annotated but undocumented still strikes through —
     * it just has nothing extra to say, and this stays {@code ""}.
     */
    public record Overload(String summary, List<Param> params, String deprecated) {

        /** An overload with no deprecation note — what every non-deprecated method parses to. */
        public Overload(String summary, List<Param> params) {
            this(summary, params, "");
        }
    }

    /** No documentation available (sources jar unresolved / offline). */
    public static final ApiDocs EMPTY = new ApiDocs(Map.of());

    /** class simpleName → method name → overloads. */
    private final Map<String, Map<String, List<Overload>>> byClass;

    public ApiDocs(Map<String, Map<String, List<Overload>>> byClass) {
        this.byClass = byClass;
    }

    /**
     * These docs and {@code other}'s together. A class both describe keeps this one's entry: two plugins may
     * not declare one class, so a clash is two classes of one simple name, and the first plugin's stays.
     */
    public ApiDocs mergedWith(ApiDocs other) {
        if (other == null || other.byClass.isEmpty()) return this;
        if (byClass.isEmpty()) return other;
        Map<String, Map<String, List<Overload>>> merged = new java.util.LinkedHashMap<>(other.byClass);
        merged.putAll(byClass);
        return new ApiDocs(Map.copyOf(merged));
    }

    /** All overloads documented for {@code class.method} (empty if none). */
    public List<Overload> overloads(String className, String method) {
        Map<String, List<Overload>> byMethod = byClass.get(className);
        if (byMethod == null) {
            return List.of();
        }
        List<Overload> list = byMethod.get(method);
        return list != null ? list : List.of();
    }

    /**
     * The best matching overload for {@code class.method} given the call's argument type simple names.
     * Prefers an exact type-name match, then an arity match, then the first documented overload — so a
     * summary/param-names are returned even when the exact overload isn't pinned down.
     */
    public Optional<Overload> lookup(String className, String method, List<String> paramTypeSimpleNames) {
        List<Overload> all = overloads(className, method);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        if (all.size() == 1) {
            return Optional.of(all.get(0));
        }
        Overload arityMatch = null;
        for (Overload o : all) {
            if (o.params().size() != paramTypeSimpleNames.size()) {
                continue;
            }
            if (arityMatch == null) {
                arityMatch = o;
            }
            if (typesMatch(o.params(), paramTypeSimpleNames)) {
                return Optional.of(o);
            }
        }
        return Optional.of(arityMatch != null ? arityMatch : all.get(0));
    }

    /** The Javadoc summary for the best-matching overload, if documented and non-blank. */
    public Optional<String> summary(String className, String method, List<String> paramTypeSimpleNames) {
        return lookup(className, method, paramTypeSimpleNames)
                .map(Overload::summary)
                .filter(s -> s != null && !s.isBlank());
    }

    private static boolean typesMatch(List<Param> params, List<String> types) {
        for (int i = 0; i < params.size(); i++) {
            if (!simple(params.get(i).type()).equals(simple(types.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** Strip generics, varargs/array markers and package qualifiers for a lenient type comparison. */
    private static String simple(String type) {
        if (type == null) {
            return "";
        }
        String t = type;
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        t = t.replace("...", "").replace("[]", "").trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
