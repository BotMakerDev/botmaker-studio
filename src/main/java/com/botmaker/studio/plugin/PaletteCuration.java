package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.MemberEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.studio.util.MethodSignature;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which members the menus <em>offer</em> on a type a plugin catalogues: the curation half of the palette.
 *
 * <p>It was half of {@code services/SdkSurfaceService} until 2026-09-28. The other half intersected the
 * catalog with the classes the type index found in the project's jar. That filtered nothing once the catalog
 * came from the project's own plugins, loaded from the same jars, so it is gone; the facade lists are
 * {@link PluginHost#menuFacades()} and {@link PluginHost#facadeNames()}, and a member's {@code @Deprecated} is
 * {@code ProjectAnalyzer.isMemberDeprecated}.
 *
 * <p><b>Filter what is offered, never what is resolved.</b> A bot already calling an overload that is no
 * longer proposed must still render, type its arguments and compile, so {@link #retainOffered} keeps the
 * call's current overload whatever the catalog says.
 *
 * <p><b>Empty means "declined to curate", never "offers nothing".</b> With no catalog served — no plugin
 * bound, or one that catalogues nothing — every answer below is "all of them", and the menus widen rather than
 * empty.
 */
public final class PaletteCuration {

    private final PaletteCatalog catalog;

    private PaletteCuration(PaletteCatalog catalog) {
        this.catalog = catalog == null ? PaletteCatalog.empty() : catalog;
    }

    /** The curation of the plugins bound to the open project. Read afresh, so a rebind is seen at once. */
    public static PaletteCuration current() {
        return new PaletteCuration(PluginHost.catalogFor());
    }

    /** The curation {@code catalog} states. */
    public static PaletteCuration of(PaletteCatalog catalog) {
        return new PaletteCuration(catalog);
    }

    /**
     * The catalogued type {@code typeName} names, or empty for one no plugin owns — "not ours, offer
     * everything".
     *
     * <p>Callers speak two vocabularies: the facade menus pass {@code "Window"} while a variable scope, an
     * in-scope type and a library class pass a fully-qualified name. A qualified name is matched exactly, so a
     * user's own {@code com.mybot.Window} cannot collide with a plugin's.
     *
     * <p><b>A bare simple name is trusted, and that is a real if narrow hole.</b> {@code MethodInvocationBlock}'s
     * class scope can be a class the user wrote, so a user class named exactly {@code Window} would be curated
     * by the plugin's answer. Only the caller holds the binding that would settle it, and the cost is a couple
     * of the user's own methods missing from one dropdown, never a wrong edit.
     */
    private Optional<FacadeEntry> curatedType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return Optional.empty();
        return typeName.indexOf('.') < 0
                ? catalog.facadeBySimpleName(typeName)
                : catalog.facade(typeName);
    }

    /**
     * The signature keys of {@code member}'s offered overloads, or {@code null} meaning <b>"all of them"</b> —
     * an uncatalogued type. An <em>empty</em> set is the other verdict: the type is curated and this member is
     * not among what it offers.
     *
     * <p>The keys are {@link MethodSignature#signatureKey()} spellings. The key is derived in three
     * vocabularies — from the analyzer's signatures, from a raw {@code MethodInfo} and from a catalog's
     * {@code MemberId} — and a mismatch has no symptom but a catalogued overload that never appears
     * ({@code SignatureKeyAgreementTest}).
     */
    private Set<String> offeredSignatures(String typeName, String member) {
        Optional<FacadeEntry> entry = curatedType(typeName);
        if (entry.isEmpty()) return null;
        return entry.get().overloads(member).stream()
                .map(MemberEntry::id)
                .map(MethodSignature::signatureKeyOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True when {@code member} earns a menu entry — any of its overloads is offered, or its type is uncurated. */
    public boolean isOffered(String typeName, String member) {
        Set<String> keys = offeredSignatures(typeName, member);
        return keys == null || !keys.isEmpty();
    }

    /**
     * {@code sigs} reduced to the overloads offered, with {@code keep} kept whatever the answer — so the ⚙ picker
     * shows the offered set <em>plus wherever this call actually is</em>. Pass {@code null} where there is no
     * current call (a fresh insert).
     */
    public List<MethodSignature> retainOffered(String typeName, String member, List<MethodSignature> sigs,
                                               MethodSignature keep) {
        Set<String> keys = offeredSignatures(typeName, member);
        if (keys == null || sigs == null) return sigs;
        String keepKey = keep == null ? null : keep.signatureKey();
        List<MethodSignature> out = sigs.stream()
                .filter(s -> keys.contains(s.signatureKey()) || s.signatureKey().equals(keepKey))
                .toList();
        // Never hand back nothing: a class curated so that every overload of a name the caller is already
        // looking at is hidden would leave an empty picker, which reads as a broken block rather than a choice.
        return out.isEmpty() ? sigs : out;
    }

    /**
     * {@code names} reduced to the members offered on {@code typeName}, with {@code keep} kept — the name-level
     * twin of {@link #retainOffered}, for a member submenu or a method dropdown.
     *
     * <p><b>Deliberately without {@link #retainOffered}'s never-hand-back-nothing guard.</b> An empty member
     * list makes the caller drop the whole submenu, which is a correct answer rather than a confusing one.
     */
    public List<String> retainOfferedNames(String typeName, List<String> names, String keep) {
        if (names == null || curatedType(typeName).isEmpty()) return names;
        return names.stream().filter(n -> isOffered(typeName, n) || n.equals(keep)).toList();
    }
}
