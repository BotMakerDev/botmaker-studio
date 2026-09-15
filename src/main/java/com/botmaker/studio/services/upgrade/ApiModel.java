package com.botmaker.studio.services.upgrade;

import com.botmaker.shared.github.SemVer;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.refactor.ApiReferences;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One plugin jar, reduced to what a compatibility question can be asked of — and the grammar the pointers
 * between two such jars are written in.
 *
 * <p>This is the <b>model half</b> of {@link PluginUpgradeService}: it reads bytecode and answers questions
 * about a single jar. It knows nothing about the bot's own source, about pairing two jars, or about what
 * the user is told — those are {@link Pairing}, {@link Redirects} and {@link UpgradeDiff}, each of
 * which is written in terms of the records here.
 *
 * <p>Everything is read from the <b>class file</b> rather than by reflection, which is why both annotations
 * are {@code @Retention(CLASS)}: the jar being read is on no classpath, and may be a version of the plugin
 * this Studio has never run against.
 *
 * <p><b>It was {@code services.SdkApiModel} until 2026-09-15.</b> The name said SDK; the code never did —
 * the only vocabulary it reads is {@code com.botmaker.plugin.api.meta}, which is the contract's and which
 * every plugin may write. The rename is what makes the class truthful about the jar it is handed.
 */
final class ApiModel {

    /**
     * The forward pointer, on the deprecated element. Class-retained, so it survives into the jar.
     *
     * <p>This is the spelling as of the 2026-08-27 move into the plugin contract, and since 2026-09-02 it is
     * the <b>only</b> one read: the vocabulary is every plugin's, not the SDK's, and knowing three
     * historical package names for one plugin's annotations was that plugin's history written down here.
     *
     * <p>Since 2026-09-15 it is also the <b>only pointer</b> read at all — see {@link Pointer} for why there
     * is no back edge.
     */
    private static final String REPLACED_BY = "com.botmaker.plugin.api.meta.ReplacedBy";

    /**
     * The release an element first appeared in, which is what gives "What's new" its eras.
     */
    private static final String SINCE = "com.botmaker.plugin.api.meta.Since";

    // Four more spellings stood here until 2026-09-02 and were read alongside the two above:
    // com.botmaker.sdk.api.meta.{ReplacedBy,Replaces,Since} (where they lived between SDK 1.1.0 and the move
    // into the contract), com.botmaker.sdk.api.{ReplacedBy,Replaces} (where they lived before 1.1.0) and
    // com.botmaker.sdk.api.meta.Scaffolding.
    //
    // They go because this class reads *any* plugin's jar, and a reader that knows three historical package
    // names for one plugin's annotations is that plugin's history written down in the editor. The vocabulary
    // is the contract's — com.botmaker.plugin.api.meta — and a plugin that wants its renames honoured uses
    // it. The SDK keeps its own names as deprecated shims pointing there, which is what @ReplacedBy is for.
    //
    // The accepted cost: a bot upgrading off a pre-2026-08-27 SDK jar sees its redirects as unpaired breaks
    // rather than as pointers, so the repair marks @NeedsReview instead of rewriting the call. Scaffolding
    // was already dead — Studio has generated no scaffold since 2026-08-25.

    /** A constructor has no name of its own; this is how the pointer grammar spells one. */
    static final String CTOR = ApiReferences.CTOR;

    private ApiModel() {
    }

    // =========================================================================
    // THE RECORDS
    // =========================================================================

    /**
     * One {@code @ReplacedBy}, read whole: where the element went, when each candidate applies, and what its
     * author said about the move.
     *
     * <p>A null {@code Pointer} means <b>no annotation at all</b>; a present one whose {@link #targets()} are
     * empty is the author saying outright that nothing takes this element's place. The two read alike and are
     * not alike, which is why the distinction is kept — and it is not a defensive branch: {@code {}} is the
     * annotation's declared default, so javac emits no value element for a bare {@code @ReplacedBy} and
     * ClassGraph hands back a null value for a present annotation.
     *
     * <p>{@code targets} is a <b>list</b> because one old member may become two — a <em>split</em>, whose
     * candidates {@link #whens()} distinguishes one sentence each. Every reader that wants one answer takes
     * the first, which is what "ordered, first preferred" means; the reader that offers the user a choice
     * takes the list.
     *
     * <p><b>There is no second half, and that is the design rather than a gap.</b> A back edge —
     * {@code @Replaces}, written on the survivor — was read here until 2026-09-15, and answered the one
     * question a forward pointer cannot: where an element went once it has been <em>deleted</em>, leaving
     * nothing to carry a pointer. It goes because nothing writes it. The contract declares only
     * {@code ReplacedBy}, and the premise that closes the gap is enforced rather than trusted: japicmp
     * refuses a removal from a plugin's published API, so the target jar still carries the deprecated
     * element and its own forward pointer, and one end answers every upgrade — chained renames included.
     *
     * <p>Both annotations are {@code @Retention(CLASS)} rather than {@code RUNTIME} for the same reason
     * {@code @Deprecated} is read from bytecode here: they are never reflected on at run time, only read off a
     * jar that is on no classpath, by the ClassGraph scan {@code TypeSummaryManager} already runs.
     */
    record Pointer(List<String> targets, List<String> whens, String note, boolean behaviourChanged) {

        /**
         * The annotation as read, or null when it is absent. A blank target is dropped rather than kept:
         * {@code {""}} and {@code {}} are the same statement, and the SDK's own gate says so.
         */
        static Pointer of(AnnotationInfo annotation) {
            if (annotation == null) return null;
            return new Pointer(strings(annotation, "value").stream().filter(t -> !t.isBlank()).toList(),
                    strings(annotation, "whens"), text(annotation, "note"),
                    flag(annotation, "behaviourChanged"));
        }
    }

    /**
     * What the plugin's own author said about a move: the sentence shown verbatim beside a redirect, and the
     * flag that says the redirect keeps its shape and changes what it does.
     *
     * <p>It is read off the {@code @ReplacedBy} on the element the bot actually names, which is the author
     * speaking about the move at the moment of the move. There is nowhere else for it to come from — see
     * {@link Pointer} for why there is no second end — and {@code behaviourChanged} is the one gap the
     * redirect check cannot see by construction, so it forces the review mark on its own.
     */
    record Advice(String note, boolean behaviourChanged) {

        static final Advice NONE = new Advice("", false);
    }

    /**
     * One public API class, reduced to what a compatibility question can be asked of.
     *
     * <p>{@code supertypes} is every class and interface above it, by simple name — the one question the diff
     * asks that a member cannot answer for itself: whether a redirect's new return value may stand where the
     * old one did. It is read from the same scan, so it covers the plugin's own hierarchy and stops at the edge
     * of the jar, which is all a check between two of that plugin's types needs.
     *
     * <p>{@code replacedBy} is the {@code @ReplacedBy} read whole (see {@link Pointer}), null when there is no
     * annotation at all. {@code since} is the release it first appeared in, {@code ""}
     * when it does not say, and {@code scaffolding} is true for a type Studio's own generated files write.
     */
    record ApiClass(String name, String simpleName, Pointer replacedBy,
                    String since, boolean scaffolding,
                    boolean deprecated, Set<String> supertypes,
                    Map<String, List<ApiMember>> byName, Set<String> deprecatedNames) {

        /** Whether this class offers {@code name} as a field — an enum constant included. */
        boolean declaresField(String name) {
            return byName.getOrDefault(name, List.of()).stream().anyMatch(ApiMember::field);
        }

        /** Whether this class offers {@code name} as something callable: a method or a constructor. */
        boolean declaresCallable(String name) {
            return byName.getOrDefault(name, List.of()).stream().anyMatch(m -> !m.field());
        }
    }

    /**
     * One public member: a method or constructor with its parameter types, or a field with none.
     *
     * <p>Fields share {@code byName} with methods rather than living in a set of their own, so the
     * deprecation rule, the additions diff and the break diff each have one thing to consult. {@code field}
     * is what keeps a constant from being mistaken for a no-argument method — a distinction that matters
     * both ways round, since turning one into the other is itself a break.
     *
     * <p>{@code type} is what it gives back, written as source names it — the <b>old</b> jar's answer, since
     * that is what the code around the call site was written for, and so what a default value standing in for
     * a removed member has to be a default of. A constructor's is its own class: {@code new ImageTemplate(…)}
     * yields an {@code ImageTemplate}.
     *
     * <p>{@code replacedBy} is the pointer, read exactly as it is on a class, and {@code since} /
     * {@code scaffolding} likewise. It sits on the <em>overload</em>, which is where the author wrote it —
     * the pairing folds the overloads of one name together, since a call site is attributed by name and
     * arity and the pointer carries no arity of its own.
     */
    record ApiMember(String name, String type, List<String> params, boolean field,
                     Pointer replacedBy,
                     String since, boolean scaffolding) {
        String signature() {
            if (field) return name;
            return (CTOR.equals(name) ? "" : name) + "(" + String.join(", ", params) + ")";
        }
    }

    // =========================================================================
    // READING ONE JAR
    // =========================================================================

    /**
     * Scans one library jar down to every public class it declares. Goes through {@link TypeSummaryManager}
     * rather than ClassGraph directly so the scan lands in the same per-jar disk cache everything else uses —
     * comparing against a given target version is fast the second time.
     *
     * <p><b>{@link TypeSummaryManager#overEverything()}, deliberately, and not the default manager.</b> The
     * default filters to the packages the <em>currently bound</em> plugins catalogue, which is the right
     * question for a menu and the wrong one here: this jar may belong to a plugin that is not bound, is being
     * installed for the first time, or failed to load, and each of those answers the empty set — so the scan
     * read empty and the report said <i>this jar has no public API at all</i>. What a jar contains is a
     * property of the jar.
     *
     * <p>The extra classes cost nothing where it matters. Attribution is by the type name the bot's own
     * source writes, so a class no bot can name is never a call site, never a break and never rewritten. The
     * one reader that lists unintersected names is {@code UpgradeDiff.additions}, which filters for
     * itself.
     */
    static Map<String, ApiClass> snapshot(Path jar) {
        TypeSummaryManager index = TypeSummaryManager.overEverything();
        index.refresh(List.of(jar.toString()));
        Map<String, ApiClass> out = new LinkedHashMap<>();
        for (ClassInfo ci : index.getAllTypes()) {
            out.put(ci.getSimpleName(), apiClassOf(ci));
        }
        return out;
    }

    private static ApiClass apiClassOf(ClassInfo ci) {
        Map<String, List<ApiMember>> byName = new LinkedHashMap<>();
        Set<String> deprecatedNames = new LinkedHashSet<>();
        Set<String> liveNames = new LinkedHashSet<>();

        List<MethodInfo> all = new ArrayList<>(ci.getMethodInfo());
        all.addAll(ci.getConstructorInfo());
        for (MethodInfo mi : all) {
            if (!mi.isPublic() || mi.isSynthetic()) continue;
            String name = mi.isConstructor() ? CTOR : mi.getName();
            String type = mi.isConstructor()
                    ? ci.getSimpleName()
                    : lastSegment(mi.getTypeSignatureOrTypeDescriptor().getResultType().toString());
            byName.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new ApiMember(name, type, paramsOf(mi), false,
                            Pointer.of(annotation(mi.getAnnotationInfo(), REPLACED_BY)),
                            text(annotation(mi.getAnnotationInfo(), SINCE), "value"),
                            false));
            // A name counts as deprecated only when every overload carrying it is — same rule as
            // SdkSurfaceService, and for the same reason: the user reads a name, not an overload.
            (mi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(name);
        }
        // Fields go through the same map and the same deprecation rule. Enum constants need no special
        // case: the compiler emits each one as a public static field of the enum type.
        for (FieldInfo fi : ci.getFieldInfo()) {
            if (!fi.isPublic() || fi.isSynthetic()) continue;
            byName.computeIfAbsent(fi.getName(), k -> new ArrayList<>())
                    .add(new ApiMember(fi.getName(), lastSegment(fi.getTypeDescriptor().toString()),
                            List.of(), true,
                            Pointer.of(annotation(fi.getAnnotationInfo(), REPLACED_BY)),
                            text(annotation(fi.getAnnotationInfo(), SINCE), "value"),
                            false));
            (fi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(fi.getName());
        }
        deprecatedNames.removeAll(liveNames);
        Set<String> supertypes = new LinkedHashSet<>();
        ci.getSuperclasses().forEach(parent -> supertypes.add(parent.getSimpleName()));
        ci.getInterfaces().forEach(parent -> supertypes.add(parent.getSimpleName()));
        return new ApiClass(ci.getName(), ci.getSimpleName(),
                Pointer.of(annotation(ci.getAnnotationInfo(), REPLACED_BY)),
                text(annotation(ci.getAnnotationInfo(), SINCE), "value"), false,
                ci.hasAnnotation(Deprecated.class.getName()), Set.copyOf(supertypes),
                Map.copyOf(byName), Set.copyOf(deprecatedNames));
    }

    /**
     * {@code name} on {@code annotations}, or {@code null} — including for an element carrying none at all,
     * which ClassGraph reports as a null list rather than an empty one.
     */
    private static AnnotationInfo annotation(AnnotationInfoList annotations, String name) {
        return annotations == null ? null : annotations.get(name);
    }

    /** One annotation element read as a list of strings — {@code String[]} being how all four spell theirs. */
    private static List<String> strings(AnnotationInfo annotation, String element) {
        if (annotation == null) return List.of();
        Object value = annotation.getParameterValues(true).getValue(element);
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(e -> String.valueOf(e).trim()).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value).trim());
    }

    /** One annotation element read as a single string, {@code ""} when the author left it at its default. */
    private static String text(AnnotationInfo annotation, String element) {
        if (annotation == null) return "";
        Object value = annotation.getParameterValues(true).getValue(element);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** One annotation element read as a flag, false when the author left it at its default. */
    private static boolean flag(AnnotationInfo annotation, String element) {
        if (annotation == null) return false;
        Object value = annotation.getParameterValues(true).getValue(element);
        return value instanceof Boolean b && b;
    }

    /**
     * Constant name → the plugin types declaring it, across <em>both</em> jars. The union is deliberate: an
     * unqualified use of a constant the target removed still has to be recognised, and only the old jar
     * knows it ever existed.
     */
    static Map<String, List<String>> fieldOwners(Map<String, ApiClass> before, Map<String, ApiClass> after) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (Map<String, ApiClass> jar : List.of(before, after)) {
            for (ApiClass klass : jar.values()) {
                for (Map.Entry<String, List<ApiMember>> entry : klass.byName().entrySet()) {
                    if (entry.getValue().stream().anyMatch(ApiMember::field)) {
                        owners.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).add(klass.simpleName());
                    }
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        owners.forEach((name, types) -> out.put(name, List.copyOf(types)));
        return Map.copyOf(out);
    }

    private static List<String> paramsOf(MethodInfo mi) {
        List<String> out = new ArrayList<>();
        for (MethodParameterInfo pi : mi.getParameterInfo()) {
            String type = pi.getTypeSignatureOrTypeDescriptor().toString();
            int dot = type.lastIndexOf('.');
            out.add(dot < 0 ? type : type.substring(dot + 1));
        }
        return out;
    }

    // =========================================================================
    // ASKING ONE CLASS A QUESTION
    // =========================================================================

    /** Whether {@code klass} offers {@code member} in the shape a call site uses it: a field, or callable. */
    static boolean declares(ApiClass klass, boolean field, String member) {
        return field ? klass.declaresField(member) : klass.declaresCallable(member);
    }

    /**
     * Whether {@code klass} offers {@code member} in the exact shape a call site uses it — a field, or a
     * callable taking that many arguments.
     *
     * <p>Arity, not types: without bindings the argument <em>types</em> at the call site are unknown, and
     * claiming a break that isn't one is worse than missing one — the user can always compile. A field shares
     * the map but has no parameter list at all, so it must not answer for arity 0.
     */
    static boolean offers(ApiClass klass, String member, int argCount) {
        if (argCount == ApiReferences.FIELD_READ) return klass.declaresField(member);
        return klass.byName().getOrDefault(member, List.of()).stream()
                .anyMatch(m -> !m.field() && m.params().size() == argCount);
    }

    /** Every overload of {@code member}, as display text — {@code "(nothing)"} when there is none. */
    static String signatures(ApiClass klass, String member) {
        List<ApiMember> overloads = klass.byName().getOrDefault(member, List.of()).stream()
                .filter(m -> !m.field()).toList();
        if (overloads.isEmpty()) return "(nothing)";
        return overloads.stream().map(ApiMember::signature).sorted().distinct()
                .reduce((a, b) -> a + " / " + b).orElse("");
    }

    // =========================================================================
    // THE POINTER GRAMMAR
    // =========================================================================

    /** {@code com.example.plugin.Key#ENTER} → {@code ENTER}; a name with no {@code #} is its own answer. */
    static String memberPart(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(hash + 1);
    }

    /** The other half: {@code …Key#ENTER} → {@code …Key}, and a bare type name is its own answer. */
    static String typePart(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
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
