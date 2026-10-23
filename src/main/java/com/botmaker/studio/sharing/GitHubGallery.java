package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.github.GitHubError;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read side of Browse Bots (no GitHub account required): every published bot, found on GitHub itself.
 *
 * <p>Since 2026-10-23 there is no gallery repository: a bot is published by giving its repository the topic
 * {@link GitHubConfig#TOPIC} ({@link BotPublisher}), and {@link #browse} is one repository search for that topic,
 * forks included. A topic anyone can put on any repository, so a result is listed only when it is a bot: its
 * newest release's pom declares a plugin of the registry ({@link PublishedBotPlugins}). Nothing about a bot is
 * stored anywhere but its own repository: its name, description, topics, stars and releases are GitHub's.
 *
 * <p>Calls are sent <em>with</em> the signed-in token when there is one. Anonymously the search is capped at 10
 * requests a minute and the release reads at 60 an hour across the whole process; the token lifts both and makes
 * the user's own not-yet-public repos readable.
 */
public final class GitHubGallery {

    /** Topics Studio reads itself ({@code botmaker-bot}, …): never shown as a bot's tags. */
    private static final String OWN_TOPIC_PREFIX = "botmaker-";
    /** GitHub's largest page, and the last page its search serves (1000 results). */
    private static final int PAGE_SIZE = 100;
    private static final int LAST_PAGE = 10;

    /**
     * Whether a repository is a bot, by {@code owner/repo@pushed_at}: a push is what can change the answer, so a
     * repository nobody pushed to since is never asked twice in one run.
     */
    private static final Map<String, Boolean> IS_BOT = new ConcurrentHashMap<>();
    /** The stars and last push the last search said, by {@code owner/repo} lower-cased. */
    private static final Map<String, RepoMeta> SEARCHED = new ConcurrentHashMap<>();

    private final GitHubClient client;
    private final GitHubAuth auth;
    private final PluginRegistry registry;

    public GitHubGallery(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
        this.registry = new PluginRegistry(client);
    }

    /** The signed-in token, or {@code null} when browsing anonymously. */
    String token() {
        return (auth != null && auth.isAuthenticated()) ? auth.token() : null;
    }

    /**
     * Every published bot: the repositories carrying the topic, forks included, that are bots. Hidden ones are
     * in it ({@link HiddenBots} is the reader's filter). Empty when GitHub cannot be searched.
     */
    public CompletableFuture<List<GalleryEntry>> browse() {
        CompletableFuture<List<PluginRegistry.Plugin>> plugins = registry.entries().exceptionally(e -> List.of());
        return search(1, new ArrayList<>()).thenCombine(plugins, (found, known) -> {
            List<CompletableFuture<Optional<GalleryEntry>>> checked = found.stream()
                    .map(item -> isBot(item, known).thenApply(bot -> bot ? Optional.of(item.entry()) : Optional.<GalleryEntry>empty()))
                    .toList();
            return CompletableFuture.allOf(checked.toArray(CompletableFuture[]::new))
                    .thenApply(done -> checked.stream().map(CompletableFuture::join).flatMap(Optional::stream).toList());
        }).thenCompose(all -> all);
    }

    /** One search result: the entry it reads as, and its stars and last push. */
    record Found(GalleryEntry entry, RepoMeta meta) {
    }

    /** Pages through the topic search from {@code page}, adding to {@code into}; what was read when one fails. */
    private CompletableFuture<List<Found>> search(int page, List<Found> into) {
        String query = URLEncoder.encode("topic:" + GitHubConfig.TOPIC + " fork:true", StandardCharsets.UTF_8);
        String url = GitHubConfig.API_BASE + "/search/repositories?q=" + query + "&sort=stars&per_page=" + PAGE_SIZE
                + "&page=" + page;
        return client.get(url, token()).exceptionally(e -> null).thenCompose(body -> {
            List<Found> read = parseSearch(body);
            into.addAll(read);
            read.forEach(f -> SEARCHED.put(key(f.entry().owner(), f.entry().repo()), f.meta()));
            if (read.size() < PAGE_SIZE || page >= LAST_PAGE) return CompletableFuture.completedFuture(into);
            return search(page + 1, into);
        });
    }

    /** A search answer's {@code items}, each as a listing; empty when there is no answer. */
    static List<Found> parseSearch(JsonNode body) {
        List<Found> out = new ArrayList<>();
        JsonNode items = body == null ? null : body.get("items");
        if (items == null || !items.isArray()) return out;
        for (JsonNode item : items) {
            String owner = item.path("owner").path("login").asText("");
            String repo = item.path("name").asText("");
            if (owner.isBlank() || repo.isBlank()) continue;
            List<String> tags = new ArrayList<>();
            for (JsonNode topic : item.path("topics")) {
                String t = topic.asText("");
                if (!t.isBlank() && !t.startsWith(OWN_TOPIC_PREFIX)) tags.add(t);
            }
            out.add(new Found(new GalleryEntry(repo, owner, repo, item.path("description").asText(""), tags),
                    new RepoMeta(item.path("stargazers_count").asInt(0),
                            parseInstant(item.path("pushed_at").asText(null)))));
        }
        return out;
    }

    /** Whether {@code found} is a bot: its newest release's pom declares one of {@code plugins}. */
    private CompletableFuture<Boolean> isBot(Found found, List<PluginRegistry.Plugin> plugins) {
        String key = key(found.entry().owner(), found.entry().repo()) + "@" + found.meta().pushedAt();
        Boolean known = IS_BOT.get(key);
        if (known != null) return CompletableFuture.completedFuture(known);
        return checkBot(found.entry(), plugins).thenApply(answer -> {
            // Only a definite answer is remembered: a rate limit, an unreachable GitHub or a registry that could not
            // be read says nothing about the repository, so it is asked again on the next browse.
            if (answer.isPresent() && !plugins.isEmpty()) IS_BOT.put(key, answer.get());
            return answer.orElse(false);
        });
    }

    /**
     * Whether {@code entry} is a bot, or empty when GitHub did not say: no release (a 404) is a definite no, while
     * any other failure on the release or its pom is no answer.
     */
    private CompletableFuture<Optional<Boolean>> checkBot(GalleryEntry entry, List<PluginRegistry.Plugin> plugins) {
        String url = GitHubConfig.API_BASE + "/repos/" + entry.owner() + "/" + entry.repo() + "/releases/latest";
        return client.getOrFail(url, token()).thenCompose(release -> {
            String tag = release.path("tag_name").asText("");
            if (tag.isEmpty()) return CompletableFuture.completedFuture(Optional.of(false));
            return client.getString("https://raw.githubusercontent.com/" + entry.owner() + "/" + entry.repo() + "/"
                    + tag + "/pom.xml").thenApply(pom -> pom == null
                    ? Optional.<Boolean>empty() : Optional.of(isBotPom(pom, plugins)));
        }).exceptionally(error -> {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return cause instanceof GitHubError gh && gh.status() == 404 ? Optional.of(false) : Optional.empty();
        });
    }

    /**
     * The listing rule: a pom that declares a plugin of the registry. A repository with the topic and no release,
     * or a release that is not a BotMaker bot, is not listed (2026-10-23).
     */
    static boolean isBotPom(String pom, List<PluginRegistry.Plugin> plugins) {
        return !PublishedBotPlugins.of(pom, plugins).isEmpty();
    }

    private static String key(String owner, String repo) {
        return (owner + "/" + repo).toLowerCase(Locale.ROOT);
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

    /** The release installing {@code entry} downloads: its newest. {@code ""} when there is nothing to install. */
    public CompletableFuture<String> installTag(GalleryEntry entry) {
        return latestReleaseTag(entry.owner(), entry.repo());
    }

    /**
     * {@code pom.xml} at the release installing {@code entry} downloads ({@link #installTag}); {@code ""} when
     * there is none or GitHub cannot be reached. What New Project reads a bot's plugins from
     * ({@link PublishedBotPlugins}), and what says a repository is a bot at all.
     */
    public CompletableFuture<String> pomAtInstallTag(GalleryEntry entry) {
        return installTag(entry).thenCompose(tag -> tag.isEmpty()
                ? CompletableFuture.completedFuture("")
                : client.getString("https://raw.githubusercontent.com/" + entry.owner() + "/" + entry.repo() + "/"
                        + tag + "/pom.xml").thenApply(pom -> pom == null ? "" : pom));
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
     * {@code owner/repo}'s star count and last-push time: what the search that listed it said, else the repo
     * object (the star count is GitHub's own, so github.com stars count too). {@code null} when the repo is
     * unreachable.
     */
    public CompletableFuture<RepoMeta> repoMeta(String owner, String repo) {
        RepoMeta searched = SEARCHED.get(key(owner, repo));
        if (searched != null) return CompletableFuture.completedFuture(searched);
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
