package com.botmaker.studio.plugin;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.studio.sharing.PluginRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * Whether a plugin jar Maven resolved is a release the catalog approved, byte for byte (2026-10-24). It replaced
 * the comparison with JitPack's {@code .sha1} of the one listed tag (2026-10-23): the catalog now pins each
 * approved release to the sha256 of its jar and of its pom as JitPack served them when the check passed, so a
 * tag moved or rebuilt since is told apart from the one approved, offline, with no request.
 *
 * <p>A version string proves nothing — a bot's pom may name its own Maven repository and serve any jar under
 * BotMaker's coordinate — so the jar and the pom beside it in {@code ~/.m2} are hashed. A release this computer
 * saw approved is remembered, by {@code groupId:artifactId@tag}, so one the catalog removed since is named as
 * removed rather than as one it never approved.
 */
final class ReleasePins {

    /** What the catalog says of a jar at a version. */
    enum Verdict {
        /** An approved release, and the jar and the pom are the ones it pinned. */
        APPROVED,
        /** An approved release, but the jar or the pom is not the one it pinned. */
        CHANGED,
        /** An approved release whose files cannot be compared: no pin, or a file unreadable. Not vouched for. */
        UNCHECKED,
        /** A release this computer saw approved, which the catalog lists no more. */
        REMOVED,
        /** Not a release the catalog approved. */
        NOT_APPROVED
    }

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "catalog.approvedReleases";

    private final Supplier<Set<String>> seen;
    private final Consumer<Set<String>> keep;

    /**
     * @param seen the releases this computer saw approved, by {@link #key}
     * @param keep remembers them
     */
    ReleasePins(Supplier<Set<String>> seen, Consumer<Set<String>> keep) {
        this.seen = seen;
        this.keep = keep;
    }

    /** Studio's: what was seen approved is remembered in the user's preferences. */
    static ReleasePins studio() {
        return new ReleasePins(ReleasePins::load, ReleasePins::save);
    }

    /** What the catalog says of {@code jar}, Maven's file of {@code row}'s plugin at {@code version}. */
    synchronized Verdict check(Path jar, PluginRegistry.Plugin row, String version) {
        String key = key(row, version);
        Optional<Catalog.Plugin.Release> release = row.release(version);
        if (release.isEmpty()) return seen.get().contains(key) ? Verdict.REMOVED : Verdict.NOT_APPROVED;
        Catalog.Plugin.Release pinned = release.get();
        if (pinned.jarSha256() == null || pinned.pomSha256() == null) return Verdict.UNCHECKED;
        Optional<String> jarSum = sha256(jar);
        Optional<String> pomSum = sha256(pomOf(jar, row.artifactId(), version));
        if (jarSum.isEmpty() || pomSum.isEmpty()) return Verdict.UNCHECKED;
        if (!jarSum.get().equals(pinned.jarSha256()) || !pomSum.get().equals(pinned.pomSha256())) {
            return Verdict.CHANGED;
        }
        Set<String> known = seen.get();
        if (!known.contains(key)) {
            Set<String> next = new LinkedHashSet<>(known);
            next.add(key);
            keep.accept(next);
        }
        return Verdict.APPROVED;
    }

    /** What a release seen approved is remembered by: {@code groupId:artifactId@tag}. */
    static String key(PluginRegistry.Plugin row, String version) {
        return row.groupId() + ":" + row.artifactId() + "@" + version;
    }

    /**
     * The pom Maven keeps beside {@code jar}: {@code <artifact>-<version>.pom}, whatever classifier the jar's own
     * name carries.
     */
    static Path pomOf(Path jar, String artifactId, String version) {
        return jar.resolveSibling(artifactId + "-" + version + ".pom");
    }

    /** {@code file}'s sha256, lower case; empty when it cannot be read. */
    static Optional<String> sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The releases seen approved, one key per line; empty when the store cannot be read. */
    static Set<String> load() {
        try {
            return of(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, ""));
        } catch (Exception e) {
            return Set.of();
        }
    }

    static Set<String> of(String stored) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : (stored == null ? "" : stored).split("\n")) {
            if (!line.isBlank()) keys.add(line.trim());
        }
        return keys;
    }

    /**
     * Best effort: a release not remembered reads as never approved once the catalog removes it. Kept in the order
     * seen, the oldest dropped first when the list outgrows what one preference holds.
     */
    static void save(Set<String> keys) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, stored(keys));
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember an approved release: " + e.getMessage());
        }
    }

    /** {@code keys} one per line, the newest {@link Preferences#MAX_VALUE_LENGTH} characters of them. */
    static String stored(Set<String> keys) {
        StringBuilder out = new StringBuilder();
        for (String key : List.copyOf(keys).reversed()) {
            if (out.length() + key.length() + 1 > Preferences.MAX_VALUE_LENGTH) break;
            out.insert(0, key + "\n");
        }
        return out.toString().strip();
    }
}
