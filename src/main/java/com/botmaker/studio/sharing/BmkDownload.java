package com.botmaker.studio.sharing;

import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * <i>Get</i> on a Get more bots card (2026-10-25, the user's call): the {@code .bmk} the catalog pins for the listed
 * tag, downloaded from the author's release into the cache, then opened as any {@code .bmk} is ({@link BmkOpen}).
 * The file is checked against the catalog's {@code bmkSha256} as it lands: a release asset replaced since the check
 * is refused here, before anything is unpacked. The caller deletes the file once it is opened.
 */
public final class BmkDownload {

    /** How far a download got: {@code total} is {@code -1} while the size is not known. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (done, total) -> { };

        void at(long done, long total);
    }

    private final HttpClient http;
    private final String apiBase;
    private final String token;

    /**
     * GitHub's API, with the signed-in token when there is one: it lifts the anonymous rate limit of the release's
     * read. The file itself comes from the asset's public address, as a catalog bot's repository is public.
     */
    public BmkDownload(String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), GitHubConfig.API_BASE, token);
    }

    BmkDownload(HttpClient http, String apiBase, String token) {
        this.http = http;
        this.apiBase = apiBase;
        this.token = token;
    }

    /** Whether {@code entry}'s listed tag has a {@code .bmk} to get: the catalog pins one. */
    public static boolean ready(GalleryEntry entry) {
        return !entry.tag().isBlank() && entry.bmkShaOf(entry.tag()).isPresent();
    }

    /** Where {@code entry}'s file is downloaded to under {@code cache}: {@code bmk/<owner>/<repo>/<file>}. */
    static Path target(Path cache, GalleryEntry entry, String file) {
        return cache.resolve("bmk").resolve(entry.owner()).resolve(entry.repo()).resolve(file);
    }

    /**
     * Downloads {@code entry}'s listed {@code .bmk} under {@code cache} and checks it. Blocking.
     *
     * @throws IOException with a sentence for the user: no file pinned, none on the release, or not the pinned one
     */
    public Path fetch(GalleryEntry entry, Path cache, Progress progress) throws IOException {
        String tag = entry.tag();
        String pinned = entry.bmkShaOf(tag).orElseThrow(() -> new IOException(
                "The catalog lists no ready-to-run file of " + entry.slug() + " " + tag + " yet."));
        Asset asset = asset(entry, tag);
        Path file = target(cache, entry, asset.name());
        // Written beside the file and moved once checked: a download cut short leaves no file that looks whole.
        Path part = file.resolveSibling(asset.name() + ".part");
        Files.createDirectories(file.getParent());
        HttpResponse<InputStream> got = send(HttpRequest.newBuilder(URI.create(asset.url()))
                .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (got.statusCode() != 200) {
            got.body().close();
            throw new IOException("GitHub answered " + got.statusCode() + " for " + asset.url() + ".");
        }
        long total = got.headers().firstValueAsLong("Content-Length").orElse(-1);
        try {
            try (InputStream in = got.body(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                for (int n; (n = in.read(buffer)) >= 0; ) {
                    out.write(buffer, 0, n);
                    done += n;
                    progress.at(done, total);
                }
            }
            if (!BotFiles.sha256(part).equalsIgnoreCase(pinned)) {
                throw new IOException("The file on " + entry.slug() + "'s " + tag + " release is not the one the "
                        + "catalog checked: it was replaced since. It was not opened.");
            }
            return Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** A release's {@code .bmk}: its file name, checked, and where it is downloaded from. */
    record Asset(String name, String url) {}

    /**
     * The download address of the release's {@code .bmk}: the asset named as {@link BotFiles#fileName} names it, else
     * the release's only {@code .bmk}.
     */
    private Asset asset(GalleryEntry entry, String tag) throws IOException {
        String release = apiBase + "/repos/" + entry.owner() + "/" + entry.repo() + "/releases/tags/" + tag;
        HttpRequest.Builder ask = HttpRequest.newBuilder(URI.create(release)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json").GET();
        if (token != null && !token.isBlank()) ask.header("Authorization", "Bearer " + token);
        HttpResponse<String> got = send(ask.build(), HttpResponse.BodyHandlers.ofString());
        if (got.statusCode() == 404) throw new IOException(entry.slug() + " has no release " + tag + " on GitHub.");
        if (got.statusCode() != 200) throw new IOException("GitHub answered " + got.statusCode() + " for " + release + ".");
        return asset(new ObjectMapper().readTree(got.body()), BotFiles.fileName(entry.name(), tag))
                .orElseThrow(() -> new IOException(entry.slug() + "'s " + tag + " release has no .bmk file."));
    }

    /**
     * In a release's JSON, the asset named {@code wanted}, else the only {@code .bmk} among its assets. Only a name
     * that is a plain file name counts: it names the file in the cache.
     */
    static Optional<Asset> asset(JsonNode release, String wanted) {
        Asset only = null;
        int bmks = 0;
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("");
            String url = asset.path("browser_download_url").asText("");
            if (url.isEmpty() || !name.matches("[A-Za-z0-9][A-Za-z0-9._ -]*")
                    || !name.toLowerCase(Locale.ROOT).endsWith(".bmk")) {
                continue;
            }
            if (name.equals(wanted)) return Optional.of(new Asset(name, url));
            only = new Asset(name, url);
            bmks++;
        }
        return bmks == 1 ? Optional.of(only) : Optional.empty();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) throws IOException {
        try {
            return http.send(request, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped while downloading.", e);
        }
    }
}
