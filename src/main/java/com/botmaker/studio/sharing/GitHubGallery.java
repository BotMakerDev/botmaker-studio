package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of the federated gallery (no GitHub account required).
 *
 * <p>The catalog is the generated {@code catalog.json} fetched from the gallery repo's raw CDN URL — a single
 * request with no API rate limit — with the legacy {@code index.json} as its fallback. Per-bot version / update information is fetched live from each author's
 * own repo via the Releases API, so the index only ever needs one entry per bot.
 *
 * <p>Those per-bot calls are sent <em>with</em> the signed-in token when there is one. Anonymously they are
 * capped at 60 requests/hour across the whole process, and browse costs one call per listed bot — so an
 * unauthenticated gallery would silently read back as "no release, ★ 0" for every entry past the cap. The
 * token also makes the user's own not-yet-public repos readable.
 */
public final class GitHubGallery {

    private final GitHubClient client;
    private final GitHubAuth auth;

    public GitHubGallery(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
    }

    /** The signed-in token, or {@code null} when browsing anonymously. */
    String token() {
        return (auth != null && auth.isAuthenticated()) ? auth.token() : null;
    }

    /**
     * Fetches every listing with its tier: {@code catalog.json}, or — when that cannot be fetched or read —
     * the legacy {@code index.json}, whose entries are all Vetted. Empty if neither can be had.
     *
     * <p>The fallback is what keeps Browse Bots working against a gallery that has not published a catalog
     * yet, and through a CDN hiccup on one file. It costs a Community bot nothing: the legacy index does not
     * hold any.
     */
    public CompletableFuture<List<GalleryEntry>> browse() {
        if (!GitHubConfig.isGalleryConfigured()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.getString(GitHubConfig.catalogRawUrl()).thenCompose(body -> {
            Optional<List<GalleryEntry>> catalog = parseCatalog(client.mapper(), body);
            if (catalog.isPresent()) return CompletableFuture.completedFuture(catalog.get());
            return client.getString(GitHubConfig.indexRawUrl())
                    .thenApply(index -> parseLegacyIndex(client.mapper(), index));
        });
    }

    /**
     * Reads {@code catalog.json}: {@code {"schemaVersion": n, "bots": [...]}}. Empty when the body is absent,
     * does not parse, or has no {@code bots} array — the three cases the legacy index is the better answer to.
     *
     * <p>A {@code schemaVersion} newer than this Studio knows is read anyway. The catalog's versions only ever
     * add fields, and {@link GalleryEntry} ignores the ones it does not know, so a lagging Studio shows less
     * rather than nothing.
     */
    static Optional<List<GalleryEntry>> parseCatalog(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode bots = mapper.readTree(body).get("bots");
            if (bots == null || !bots.isArray()) return Optional.empty();
            return Optional.of(List.of(mapper.treeToValue(bots, GalleryEntry[].class)));
        } catch (Exception e) {
            System.err.println("Failed to parse gallery " + GitHubConfig.CATALOG_PATH + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Reads the legacy {@code index.json} array, marking every entry Vetted — which is all that file holds. */
    static List<GalleryEntry> parseLegacyIndex(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            return Arrays.stream(mapper.readValue(body, GalleryEntry[].class))
                    .map(e -> e.withTier(GalleryTier.VETTED))
                    .toList();
        } catch (Exception e) {
            System.err.println("Failed to parse gallery " + GitHubConfig.INDEX_PATH + ": " + e.getMessage());
            return List.of();
        }
    }

    /** The listing for {@code owner/repo} in {@code catalog}, compared as GitHub does, without case. */
    public static Optional<GalleryEntry> find(List<GalleryEntry> catalog, String owner, String repo) {
        return catalog.stream()
                .filter(e -> e.owner().equalsIgnoreCase(owner) && e.repo().equalsIgnoreCase(repo))
                .findFirst();
    }

    /**
     * Resolves the latest release tag for {@code owner/repo} (the author's newest published version), or
     * {@code ""} if the repo has no releases / is unreachable. Used for "Update available" checks.
     */
    public CompletableFuture<String> latestReleaseTag(String owner, String repo) {
        String url = GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo + "/releases/latest";
        return client.get(url, token()).thenApply(node -> {
            if (node == null) return "";
            JsonNode tag = node.get("tag_name");
            return tag == null ? "" : tag.asText("");
        });
    }

    /**
     * The release installing {@code entry} downloads — see {@link GalleryEntry#installTag}. The newest
     * release is asked for only when the entry pins none, so a Vetted bot installs with no API call at all.
     * {@code ""} when there is nothing to install.
     */
    public CompletableFuture<String> installTag(GalleryEntry entry) {
        String pinned = entry.installTag("");
        if (!pinned.isEmpty()) return CompletableFuture.completedFuture(pinned);
        return latestReleaseTag(entry.owner(), entry.repo());
    }

    /**
     * Live per-repo signals used for gallery sorting/badges: star count and last-push time (epoch seconds).
     * {@link #UNKNOWN} is the <em>display</em> placeholder for "not fetched yet" only — a failed fetch
     * resolves to {@code null}, so callers can tell "we don't know" from "genuinely zero stars" and never
     * overwrite a count they already have.
     */
    public record RepoMeta(int stars, long pushedAt) {
        public static final RepoMeta UNKNOWN = new RepoMeta(0, 0);
    }

    /**
     * Fetches {@code owner/repo}'s live star count and last-push time from the repo object (the star count is
     * GitHub's own, so github.com stars count too). {@code null} when the repo is unreachable.
     */
    public CompletableFuture<RepoMeta> repoMeta(String owner, String repo) {
        String url = GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo;
        return client.get(url, token()).thenApply(node -> {
            if (node == null) return null;
            int stars = node.path("stargazers_count").asInt(0);
            long pushed = parseInstant(node.path("pushed_at").asText(null));
            return new RepoMeta(stars, pushed);
        });
    }

    /** True when the signed-in user (via {@code token}) has starred {@code owner/repo}. */
    public CompletableFuture<Boolean> isStarred(String owner, String repo, String token) {
        return client.isNoContent(GitHubConfig.API_BASE + "/user/starred/" + owner + "/" + repo, token);
    }

    /** Stars or unstars {@code owner/repo} for the signed-in user. Requires a token. */
    public CompletableFuture<Void> setStarred(String owner, String repo, boolean starred, String token) {
        String url = GitHubConfig.API_BASE + "/user/starred/" + owner + "/" + repo;
        return (starred ? client.put(url, java.util.Map.of(), token) : client.delete(url, token))
                .thenApply(n -> null);
    }

    private static long parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return java.time.Instant.parse(iso).getEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }
}
