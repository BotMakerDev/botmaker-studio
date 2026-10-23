package com.botmaker.studio.plugin;

import com.botmaker.studio.sharing.PluginRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a jar is the release JitPack built for a catalog row's tag, byte for byte (2026-10-23, Phase 7b).
 *
 * <p>A version string proves nothing: a bot's pom may name its own Maven repository and serve any jar as
 * {@code com.github.BotMakerDev:botmaker-sdk:v1.5.0}, and Maven checks it against that repository's own
 * {@code .sha1}. So before an official or trusted row's tag loads unasked, the jar's SHA-1 is compared with the
 * one JitPack publishes for that tag. JitPack's answer is remembered per computer by coordinate and tag, so a
 * tag checked once is checked offline afterwards; a tag never checked, offline, is not counted as the release.
 */
final class ReleaseChecksums {

    private static final String JITPACK = "https://jitpack.io/";
    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "catalog.releaseChecksums";
    private static final Pattern SHA1 = Pattern.compile("([0-9a-fA-F]{40})(\\s|$)");

    private final Function<String, Optional<String>> fetch;
    private final Supplier<Map<String, String>> kept;
    private final Consumer<Map<String, String>> keep;

    /**
     * @param fetch the body at a URL, empty when it could not be read or did not answer 200
     * @param kept  the checksums remembered on this computer, by {@link #key}
     * @param keep  remembers them
     */
    ReleaseChecksums(Function<String, Optional<String>> fetch, Supplier<Map<String, String>> kept,
                     Consumer<Map<String, String>> keep) {
        this.fetch = fetch;
        this.kept = kept;
        this.keep = keep;
    }

    /**
     * Studio's: JitPack over HTTP, remembered in the user's preferences. No redirect is followed: JitPack answers
     * a {@code .sha1} itself, and the checksum must come from it.
     */
    static ReleaseChecksums studio() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new ReleaseChecksums(url -> get(http, url), ReleaseChecksums::load, ReleaseChecksums::save);
    }

    /**
     * Whether {@code jar} is the jar JitPack built for {@code row}'s tag; false whenever that cannot be told. A
     * jar that differs from the remembered checksum is compared once more with a fresh one: JitPack rebuilds a tag
     * it evicted, and its jars are not reproducible.
     */
    synchronized boolean isRelease(Path jar, PluginRegistry.Plugin row) {
        if (row.tag().isBlank() || !row.isInstallable()) return false;
        Optional<String> actual = sha1(jar);
        if (actual.isEmpty()) return false;
        String key = key(row);
        String remembered = kept.get().get(key);
        if (actual.get().equals(remembered)) return true;
        Optional<String> published = fetch.apply(url(row)).flatMap(ReleaseChecksums::parse);
        if (published.isEmpty()) return false;
        if (!published.get().equals(remembered)) keep.accept(with(kept.get(), key, published.get()));
        return published.get().equals(actual.get());
    }

    /**
     * What a checksum is remembered by: {@code groupId:artifactId@tag}. One per coordinate is kept
     * ({@link #with}): a catalog entry lists one tag, so the list stays as long as the catalog's plugins and fits
     * a preference.
     */
    static String key(PluginRegistry.Plugin row) {
        return row.groupId() + ":" + row.artifactId() + "@" + row.tag();
    }

    /** {@code sums} with {@code key}'s checksum, and none for another tag of its coordinate. */
    static Map<String, String> with(Map<String, String> sums, String key, String sha1) {
        String coordinate = key.substring(0, key.lastIndexOf('@') + 1);
        Map<String, String> next = new LinkedHashMap<>();
        sums.forEach((k, v) -> {
            if (!k.startsWith(coordinate)) next.put(k, v);
        });
        next.put(key, sha1);
        return next;
    }

    /** Where JitPack publishes the SHA-1 of a row's jar at its tag. */
    static String url(PluginRegistry.Plugin row) {
        return JITPACK + row.groupId().replace('.', '/') + "/" + row.artifactId() + "/" + row.tag() + "/"
                + row.artifactId() + "-" + row.tag() + ".jar.sha1";
    }

    /**
     * The checksum a {@code .sha1} body starts with, lower case; empty when it starts with anything else, so a
     * page that only mentions a hash (an error page, a build log) is never taken for one.
     */
    static Optional<String> parse(String body) {
        if (body == null) return Optional.empty();
        Matcher m = SHA1.matcher(body.strip());
        return m.lookingAt() ? Optional.of(m.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /** {@code jar}'s SHA-1, lower case; empty when it cannot be read. */
    static Optional<String> sha1(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> get(HttpClient http, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The remembered checksums, one {@code key sha1} per line; empty when the store cannot be read. */
    static Map<String, String> load() {
        try {
            return of(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, ""));
        } catch (Exception e) {
            return Map.of();
        }
    }

    static Map<String, String> of(String stored) {
        Map<String, String> sums = new LinkedHashMap<>();
        for (String line : (stored == null ? "" : stored).split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length == 2) sums.put(parts[0], parts[1]);
        }
        return sums;
    }

    /** Best effort: a checksum that cannot be kept is fetched again next time. */
    static void save(Map<String, String> sums) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, String.join("\n", sums.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue()).sorted().toList()));
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember a release's checksum: " + e.getMessage());
        }
    }
}
