package com.botmaker.studio.plugin;

import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublisherRank;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

/**
 * The community plugins this computer's user agreed to run (2026-10-23). A plugin's code runs in Studio the moment
 * it loads, with the user's own permissions, and the catalog lists anyone's plugin without a review — so before a
 * community plugin is first loaded on this computer, Studio asks ({@link PluginGate}), and a yes is kept here.
 * Nothing about it is published.
 *
 * <p>Kept by {@link #key}: {@code owner/repo#id} for a catalog row, so one yes covers every release of that entry;
 * the coordinate for a plugin the catalog does not list. The rules are on the value ({@link #needsAsking},
 * {@link #with}) so they are tested without the preference store; {@link #load} and {@link #save} are the edges.
 */
public record CommunityConsent(Set<String> allowed) {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "catalog.communityConsent";

    public CommunityConsent {
        allowed = Set.copyOf(allowed);
    }

    /** This computer's list; empty when the preference store cannot be read, so everything is asked again. */
    public static CommunityConsent load() {
        try {
            return of(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, ""));
        } catch (Exception e) {
            return new CommunityConsent(Set.of());
        }
    }

    /** The list a preference holds: one key per line. */
    static CommunityConsent of(String stored) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : (stored == null ? "" : stored).split("\n")) {
            if (!line.isBlank()) keys.add(line.trim());
        }
        return new CommunityConsent(keys);
    }

    /** Writes this list as this computer's. Best effort: a yes that cannot be kept is asked again next time. */
    public void save() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, String.join("\n", allowed.stream().sorted().toList()));
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember the community plugins you agreed to: " + e.getMessage());
        }
    }

    public boolean allows(String key) {
        return allowed.contains(key);
    }

    /** This list with {@code key} agreed to. */
    public CommunityConsent with(String key) {
        Set<String> next = new LinkedHashSet<>(allowed);
        next.add(key);
        return new CommunityConsent(next);
    }

    /**
     * What a yes for this plugin is kept as: {@code owner/repo#id} for a community row, so one yes covers every
     * release of that entry; {@code owner/repo#id@version} for another version of an official or trusted row,
     * which its rank does not vouch for; the coordinate for a plugin the catalog does not list.
     */
    public static String key(String coordinate, String version, Optional<PluginRegistry.Plugin> row) {
        return row.map(r -> r.repo().toLowerCase(Locale.ROOT) + "#" + r.id()
                        + (r.rank() == PublisherRank.COMMUNITY ? "" : "@" + version))
                .orElse(coordinate);
    }

    /**
     * Whether loading {@code version} of the plugin at {@code coordinate} needs the user's yes first.
     *
     * <p>Never for a dev build (the user built it on this computer). Never for an official or trusted catalog
     * row, nor the signed-in user's own row, at the release it lists — and only that one (the user's call,
     * 2026-10-23): any pom may name its own Maven repository and serve another version, or another jar at the
     * same version, under the same coordinate, so a row vouches for the jar JitPack built at the tag the
     * catalog's check passed ({@code isRelease}) and nothing else. Always for a community row and for a
     * coordinate the catalog does not list, the user's own groupId included (Phase 7b: any repository can serve
     * {@code com.github.<login>}) — except, when no catalog could be read at all (offline on a first start, none
     * kept), a coordinate under BotMaker's own groupId: the SDK and basics must load with no prompt.
     *
     * @param coordinate    {@code groupId:artifactId}, blank when the jar's could not be read
     * @param version       the jar's version, as its repository directory spells it
     * @param row           the catalog row for that coordinate, empty when none lists it
     * @param catalogRead   whether a catalog was read, from GitHub or kept from the last read
     * @param signedInLogin the GitHub login signed in here, blank or null when nobody is
     * @param isRelease     whether the jar is the one JitPack built for the row's tag; asked only of a row that
     *                      vouches at that tag
     */
    public static boolean needsAsking(String coordinate, String version, Optional<PluginRegistry.Plugin> row,
                                      boolean catalogRead, String signedInLogin, boolean devBuild,
                                      BooleanSupplier isRelease) {
        if (devBuild) return false;
        if (row.isPresent()) {
            PluginRegistry.Plugin r = row.get();
            boolean vouches = r.rank() != PublisherRank.COMMUNITY || isOwn(r, signedInLogin);
            return !(vouches && vouchedFor(r, version) && isRelease.getAsBoolean());
        }
        int colon = coordinate == null ? -1 : coordinate.indexOf(':');
        String group = colon < 0 ? "" : coordinate.substring(0, colon);
        return catalogRead || !DuplicatePlugins.isBotMakerGroup(group);
    }

    /** Whether {@code row}'s repository belongs to the signed-in login. */
    private static boolean isOwn(PluginRegistry.Plugin row, String signedInLogin) {
        String login = signedInLogin == null ? "" : signedInLogin.trim();
        int slash = row.repo().indexOf('/');
        return !login.isEmpty() && slash > 0 && row.repo().substring(0, slash).equalsIgnoreCase(login);
    }

    /** Whether {@code version} is the release {@code row} lists: its tag as git spells it. */
    public static boolean vouchedFor(PluginRegistry.Plugin row, String version) {
        return !row.tag().isBlank() && row.tag().equals(version);
    }
}
