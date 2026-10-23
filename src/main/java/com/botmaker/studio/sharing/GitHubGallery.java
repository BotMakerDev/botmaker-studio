package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.github.GitHubError;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of Browse Bots (no GitHub account required): every listed bot, and the GitHub calls a bot's card makes.
 *
 * <p>Since 2026-10-23 the bots are the catalog's ({@link CatalogClient}): one file, {@code catalog.json}, that the
 * catalog repository's CI builds from its entries, each checked by machine before it merged. The topic search, the
 * pom read per row that said whether a repository was a bot, and the read per fork for its parent went with it:
 * the catalog says each, once, for every reader.
 *
 * <p>Calls to GitHub are sent <em>with</em> the signed-in token when there is one: anonymously they are capped at 60
 * an hour across the whole process, and the token makes the user's own not-yet-public repos readable.
 */
public final class GitHubGallery {

    private final GitHubClient client;
    private final GitHubAuth auth;
    private final CatalogClient catalog;

    public GitHubGallery(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
        this.catalog = CatalogClient.session();
    }

    /** The signed-in token, or {@code null} when browsing anonymously. */
    String token() {
        return (auth != null && auth.isAuthenticated()) ? auth.token() : null;
    }

    /**
     * Every listed bot. Hidden ones are in it ({@link HiddenBots} is the reader's filter). Empty when no catalog
     * can be read.
     */
    public CompletableFuture<List<GalleryEntry>> browse() {
        return catalog.fetch().thenApply(CatalogIndex::bots);
    }

    /** The listing for {@code owner/repo} in {@code catalog}, compared as GitHub does, without case. */
    public static Optional<GalleryEntry> find(List<GalleryEntry> catalog, String owner, String repo) {
        return catalog.stream()
                .filter(e -> e.owner().equalsIgnoreCase(owner) && e.repo().equalsIgnoreCase(repo))
                .findFirst();
    }

    /**
     * The tag the catalog lists for {@code owner/repo}, {@code ""} when it lists none: what an installed copy is
     * offered as its update, since a newer release has passed no check.
     */
    public CompletableFuture<String> listedTag(String owner, String repo) {
        return browse().thenApply(bots -> find(bots, owner, repo).map(GalleryEntry::tag).orElse(""));
    }

    /**
     * Resolves the latest release tag for {@code owner/repo}, or {@code ""} if the repo has no releases / is
     * unreachable. What the Publish sheet numbers the user's own next release from.
     */
    public CompletableFuture<String> latestReleaseTag(String owner, String repo) {
        String url = GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo + "/releases/latest";
        return client.get(url, token()).thenApply(node -> {
            JsonNode tag = node == null ? null : node.get("tag_name");
            return tag == null ? "" : tag.asText("");
        });
    }

    // mirrorTags() and the gallery's copy of every release (GalleryMirror) went on 2026-10-23 with the gallery
    // repository: a bot whose author removed it says so and keeps running, and is no longer installable.

    /**
     * Whether GitHub says {@code owner/repo} does not exist — a 404, never an unreachable network or a rate
     * limit, which say nothing about the repository.
     */
    public CompletableFuture<Boolean> repositoryGone(String owner, String repo) {
        return client.getOrFail(GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo, token())
                .handle((node, error) -> {
                    if (error == null) return false;
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    return cause instanceof GitHubError gh && gh.status() == 404;
                });
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

    /** {@code owner/repo}'s star count and last-push time, from the repo object. {@code null} when unreachable. */
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
        return (starred ? client.put(url, Map.of(), token) : client.delete(url, token))
                .thenApply(n -> null);
    }

    private static long parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return 0;
        try {
            return Instant.parse(iso).getEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }
}
