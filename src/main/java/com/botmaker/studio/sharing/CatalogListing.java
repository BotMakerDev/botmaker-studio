package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.github.GitHubError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * A bot's entry in the catalog, {@code bots/<owner>__<repo>.json}, written or removed by one pull request from the
 * author's account (2026-10-23). Nobody reviews it: the catalog's {@code validate} check runs on it and
 * {@code automerge.yml} merges it when the check passes ({@code docs/superpowers/specs/2026-10-23-catalog-design.md}).
 *
 * <p>The same steps as the cli's {@code CatalogPr}, through GitHub's API instead of {@code gh} — Studio never
 * calls the cli. An account that can push to the catalog gets a branch there; everyone else a fork, and a branch
 * of the fork. The branch is {@code entry/<path>}, reset to the catalog's {@code main} each time: a fork's
 * {@code main} diverges at the first squash-merge, so a second pull request from it would carry the first entry
 * again, and a branch per entry carries exactly one file's change, its open pull request updated in place.
 *
 * <p>Blocking; off the FX thread.
 */
public final class CatalogListing {

    private final GitHubClient client;

    public CatalogListing(GitHubClient client) {
        this.client = client;
    }

    /**
     * What a bot's entry file holds, exactly: the cli's {@code BotEntry}, whose fields the check refuses any more
     * of.
     *
     * @param repo     {@code owner/name}, as GitHub spells it
     * @param tag      the release people start from, as git spells it
     * @param detached a fork that stopped following its original (<i>Stop following</i>): no "based on …"
     */
    public record BotEntry(String repo, String tag, boolean detached) {

        public BotEntry {
            repo = repo == null ? "" : repo.trim();
            tag = tag == null ? "" : tag.trim();
        }

        /** {@code bots/<owner>__<name>.json}: a login holds no underscore, so the first {@code __} splits it. */
        public String path() {
            return CatalogListing.path(repo);
        }

        /** The file's text as the cli writes it: pretty-printed, {@code detached} only when true. */
        String json(ObjectMapper mapper) throws IOException {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("repo", repo);
            fields.put("tag", tag);
            if (detached) fields.put("detached", true);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fields) + "\n";
        }

        /** A Contents API file read as an entry; empty when it is not one. */
        static Optional<BotEntry> of(ObjectMapper mapper, JsonNode file) {
            if (file == null) return Optional.empty();
            try {
                String encoded = file.path("content").asText("").replaceAll("\\s", "");
                JsonNode json = mapper.readTree(Base64.getDecoder().decode(encoded));
                String repo = json.path("repo").asText("");
                String tag = json.path("tag").asText("");
                if (repo.isBlank() || tag.isBlank()) return Optional.empty();
                return Optional.of(new BotEntry(repo, tag, json.path("detached").asBoolean(false)));
            } catch (Exception unreadable) {
                return Optional.empty();
            }
        }
    }

    /** The entry path of the bot at {@code owner/name}. */
    public static String path(String repo) {
        int slash = repo.indexOf('/');
        return "bots/" + repo.substring(0, Math.max(slash, 0)) + "__" + repo.substring(slash + 1) + ".json";
    }

    /** {@code entry/<path>}: one branch per entry, as the cli names it. */
    static String branch(String path) {
        return "entry/" + path;
    }

    /**
     * The bot's entry on the catalog's {@code main}, if it is listed. Fails when GitHub cannot say: only its 404
     * means "not listed", never a rate limit or an outage.
     */
    public Optional<BotEntry> listed(String repo, String token) throws IOException {
        return BotEntry.of(client.mapper(), read(contentsUrl(catalogApi(), path(repo)) + "?ref="
                + GitHubConfig.CATALOG_BRANCH, token).orElse(null));
    }

    /**
     * The entry as it will stand once {@code login}'s open pull request for it merges — empty for a removal — or,
     * with none open, as {@code main} has it. What a re-publish or <i>Stop following</i> builds on, so neither
     * undoes the other while it waits for the check.
     */
    public Optional<BotEntry> pending(String repo, String login, String token) throws IOException {
        Optional<JsonNode> pr = openPullRequest(path(repo), login, token);
        return pr.isPresent() ? entryAt(pr.get(), path(repo), token) : listed(repo, token);
    }

    /**
     * One pull request's change, decided from what {@code main} holds: the cli's {@code CatalogPr.Change}.
     *
     * @param content the file's new text, or empty to remove it
     */
    record Change(String path, Optional<String> content, String title, String body) {

        /** {@code entry/<path>}. */
        String branch() {
            return CatalogListing.branch(path);
        }

        /** Writes {@code entry} over {@code current}; empty when {@code current} already is it. */
        static Optional<Change> put(ObjectMapper mapper, BotEntry entry, Optional<BotEntry> current)
                throws IOException {
            if (current.equals(Optional.of(entry))) return Optional.empty();
            String verb = current.isEmpty() ? "add " : "update ";
            return Optional.of(new Change(entry.path(), Optional.of(entry.json(mapper)),
                    "bot: " + verb + entry.repo() + " " + entry.tag(),
                    "Opened by BotMaker Studio. The catalog's check runs on it and merges it when it passes."));
        }

        /** Removes {@code repo}'s entry; empty when {@code current} says there is none. */
        static Optional<Change> remove(String repo, Optional<BotEntry> current) {
            if (current.isEmpty()) return Optional.empty();
            return Optional.of(new Change(CatalogListing.path(repo), Optional.empty(), "bot: remove " + repo,
                    "Unpublished from BotMaker Studio. The catalog's check runs on it and merges it when it passes."));
        }
    }

    /**
     * Opens, or updates, the pull request writing {@code entry}, as {@code login}. {@link ListingStatus.State#UNCHANGED}
     * when {@code main} already says exactly this; an open pull request that would change it is then closed.
     */
    public ListingStatus put(BotEntry entry, String login, String token) throws IOException {
        Optional<BotEntry> main = listed(entry.repo(), token);
        Optional<JsonNode> pr = openPullRequest(entry.path(), login, token);
        if (pr.isPresent() && entryAt(pr.get(), entry.path(), token).equals(Optional.of(entry))) {
            return statusOf(pr.get(), token);
        }
        if (main.equals(Optional.of(entry))) {
            close(pr, token);
            return ListingStatus.of(ListingStatus.State.UNCHANGED);
        }
        return open(Change.put(client.mapper(), entry, main).orElseThrow(), login, token);
    }

    /**
     * Opens the pull request removing {@code repo}'s entry. {@link ListingStatus.State#NOT_LISTED} when {@code main}
     * has none; an open pull request that would add it is then closed.
     */
    public ListingStatus remove(String repo, String login, String token) throws IOException {
        Optional<BotEntry> main = listed(repo, token);
        Optional<JsonNode> pr = openPullRequest(path(repo), login, token);
        if (main.isEmpty()) {
            close(pr, token);
            return ListingStatus.of(ListingStatus.State.NOT_LISTED);
        }
        if (pr.isPresent() && entryAt(pr.get(), path(repo), token).isEmpty()) return statusOf(pr.get(), token);
        return open(Change.remove(repo, main).orElseThrow(), login, token);
    }

    /** {@code login}'s open pull request for {@code path}, from the branch {@link #open} uses. */
    private Optional<JsonNode> openPullRequest(String path, String login, String token) throws IOException {
        JsonNode open = read(catalogApi() + "/pulls?state=open&head=" + headOwner(login, token) + ":" + branch(path),
                token).orElse(null);
        return open != null && open.isArray() && !open.isEmpty() ? Optional.of(open.get(0)) : Optional.empty();
    }

    /** The entry file on an open pull request's head branch; empty when the branch removes it. */
    private Optional<BotEntry> entryAt(JsonNode pr, String path, String token) throws IOException {
        String head = pr.path("head").path("repo").path("full_name").asText("");
        if (head.isBlank()) return Optional.empty();
        return BotEntry.of(client.mapper(), read(contentsUrl(GitHubConfig.API_BASE + "/repos/" + head, path)
                + "?ref=" + pr.path("head").path("ref").asText(""), token).orElse(null));
    }

    /** Closes {@code pr}, a pull request whose change {@code main} already holds or no longer needs. */
    private void close(Optional<JsonNode> pr, String token) throws IOException {
        if (pr.isEmpty()) return;
        try {
            client.patch(catalogApi() + "/pulls/" + pr.get().path("number").asInt(), Map.of("state", "closed"), token)
                    .join();
        } catch (Exception e) {
            throw new IOException("Could not close the catalog's pull request " + pr.get().path("html_url").asText("")
                    + ": " + rootMessage(e), e);
        }
    }

    /**
     * What the catalog says of {@code repo}'s entry: the newest pull request from {@code login}'s branch for it,
     * read through its check run, labels and the catalog's comment — or, with none, whether {@code main} lists it.
     */
    public ListingStatus status(String repo, String login, String token) throws IOException {
        boolean listed = listed(repo, token).isPresent();
        String head = headOwner(login, token) + ":" + branch(path(repo));
        JsonNode prs = client.get(catalogApi() + "/pulls?state=all&per_page=1&head=" + head, token).join();
        if (prs != null && prs.isArray() && !prs.isEmpty()) {
            ListingStatus status = statusOf(prs.get(0), token);
            // The newest pull request may have been a removal; what main holds settles it.
            if (status.state() == ListingStatus.State.LISTED && !listed) {
                return ListingStatus.of(ListingStatus.State.NOT_LISTED);
            }
            if (status.state() != ListingStatus.State.CLOSED) return status;
        }
        return ListingStatus.of(listed ? ListingStatus.State.LISTED : ListingStatus.State.NOT_LISTED);
    }

    /**
     * Why the catalog would refuse {@code login}'s entry for {@code release}, a repository's release object, or
     * empty: the rules Studio can check before opening anything (rules 3 and 5; rules 1 and 2 hold by how
     * {@link BotEntry} is built). A release made by an Action counts as the owner's.
     *
     * @param owner the repository's owner login
     */
    public static Optional<String> refusal(JsonNode release, String login, String owner) {
        if (release == null) return Optional.of("The release is not on GitHub, so the catalog cannot list it.");
        if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
            return Optional.of("The release " + release.path("tag_name").asText("") + " is a draft or a prerelease; "
                    + "the catalog lists a published release only. Publish it on github.com, then retry.");
        }
        String author = release.path("author").path("login").asText("");
        if (author.equalsIgnoreCase(login)) return Optional.empty();
        if (author.equals(ACTIONS_BOT) && owner.equalsIgnoreCase(login)) return Optional.empty();
        return Optional.of("The release " + release.path("tag_name").asText("") + " was made by "
                + (author.isBlank() ? "someone else" : author) + ", and the catalog lists a release only from whoever "
                + "made it" + (author.equals(ACTIONS_BOT) ? " (an Action's, from the repository's owner)" : "")
                + ". Publish from that account, or cut a release of your own.");
    }

    /** Who makes a release from a workflow. */
    static final String ACTIONS_BOT = "github-actions[bot]";

    // -------------------------------------------------------------------------
    // The pull request
    // -------------------------------------------------------------------------

    private ListingStatus open(Change change, String login, String token) throws IOException {
        try {
            boolean push = canPush(token);
            String headOwner = push ? GitHubConfig.CATALOG_OWNER : login;
            String headApi = push ? catalogApi() : fork(login, token);
            String branch = change.branch();
            String path = change.path();
            prepareBranch(headApi, branch, token);
            String sha = fileSha(contentsUrl(headApi, path), branch, token);
            if (change.content().isPresent()) {
                Map<String, Object> write = new LinkedHashMap<>();
                write.put("message", change.title());
                write.put("content", Base64.getEncoder().encodeToString(
                        change.content().get().getBytes(StandardCharsets.UTF_8)));
                write.put("branch", branch);
                if (sha != null) write.put("sha", sha);
                client.put(contentsUrl(headApi, path), write, token).join();
            } else {
                if (sha == null) return ListingStatus.of(ListingStatus.State.NOT_LISTED);
                client.delete(contentsUrl(headApi, path), Map.of("message", change.title(), "sha", sha,
                        "branch", branch), token).join();
            }
            JsonNode open = client.get(catalogApi() + "/pulls?state=open&head=" + headOwner + ":" + branch, token)
                    .join();
            if (open != null && open.isArray() && !open.isEmpty()) return statusOf(open.get(0), token);
            JsonNode pr = client.post(catalogApi() + "/pulls", Map.of(
                    "title", change.title(),
                    "head", headOwner + ":" + branch,
                    "base", GitHubConfig.CATALOG_BRANCH,
                    "body", change.body()), token).join();
            return ListingStatus.ofPullRequest(pr, "", "", "");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("The catalog's pull request could not be opened: " + rootMessage(e), e);
        }
    }

    /**
     * Whether the account can push to the catalog itself: GitHub forks no repository into its own owner. Fails
     * when the catalog cannot be read, rather than sending an admin down the fork path.
     */
    private boolean canPush(String token) throws IOException {
        JsonNode catalog = read(catalogApi(), token)
                .orElseThrow(() -> new IOException("The catalog " + GitHubConfig.CATALOG_OWNER + "/"
                        + GitHubConfig.CATALOG_REPO + " could not be found on GitHub."));
        return catalog.path("permissions").path("push").asBoolean(false);
    }

    /** Whose branch a pull request comes from: the catalog's owner for an account that can push there. */
    private String headOwner(String login, String token) throws IOException {
        return canPush(token) ? GitHubConfig.CATALOG_OWNER : login;
    }

    /** A GET that tells "not there" (404: empty) from "GitHub could not say" (an {@link IOException}). */
    private Optional<JsonNode> read(String url, String token) throws IOException {
        try {
            return Optional.of(client.getOrFail(url, token).join());
        } catch (CompletionException e) {
            if (e.getCause() instanceof GitHubError error) {
                if (error.status() == 404) return Optional.empty();
                throw new IOException(error.getMessage(), error);
            }
            throw new IOException(rootMessage(e), e);
        }
    }

    /**
     * The account's fork of the catalog, made when there is none, as its API URL. GitHub makes a fork in the
     * background, so it is asked for a few times before this gives up.
     */
    private String fork(String login, String token) throws IOException {
        JsonNode made = client.post(catalogApi() + "/forks", Map.of(), token).join();
        String fullName = made == null ? "" : made.path("full_name").asText("");
        String api = GitHubConfig.API_BASE + "/repos/" + (fullName.isBlank()
                ? login + "/" + GitHubConfig.CATALOG_REPO : fullName);
        for (int attempt = 0; attempt < 10; attempt++) {
            if (client.get(api + "/git/ref/heads/" + GitHubConfig.CATALOG_BRANCH, token).join() != null) return api;
            if (!sleep(2000)) break;
        }
        throw new IOException("Your fork of the catalog is not ready yet; retry in a minute.");
    }

    /**
     * Points {@code branch} of {@code headApi} at the catalog's current {@code main}, creating it if needed. A fork
     * shares its network's objects, so a ref may name a commit only the catalog has; should GitHub refuse that
     * anyway, the fork's {@code main} is synced first and the branch starts there.
     */
    private void prepareBranch(String headApi, String branch, String token) {
        JsonNode tip = client.get(catalogApi() + "/git/ref/heads/" + GitHubConfig.CATALOG_BRANCH, token).join();
        String sha = tip == null ? null : tip.path("object").path("sha").asText(null);
        try {
            pointBranch(headApi, branch, sha, token);
        } catch (RuntimeException refused) {
            if (headApi.equals(catalogApi())) throw refused;
            client.post(headApi + "/merge-upstream", Map.of("branch", GitHubConfig.CATALOG_BRANCH), token).join();
            JsonNode forkMain = client.get(headApi + "/git/ref/heads/" + GitHubConfig.CATALOG_BRANCH, token).join();
            pointBranch(headApi, branch, forkMain == null ? null : forkMain.path("object").path("sha").asText(null),
                    token);
        }
    }

    private void pointBranch(String headApi, String branch, String sha, String token) {
        if (sha == null) {
            throw new IllegalStateException("The catalog's " + GitHubConfig.CATALOG_BRANCH + " could not be read.");
        }
        if (client.get(headApi + "/git/ref/heads/" + branch, token).join() != null) {
            client.patch(headApi + "/git/refs/heads/" + branch, Map.of("sha", sha, "force", true), token).join();
        } else {
            client.post(headApi + "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", sha), token).join();
        }
    }

    /** Reads one pull request: its {@code validate} run, what a refusal said, and the catalog's comment. */
    private ListingStatus statusOf(JsonNode pr, String token) {
        if (!"open".equals(pr.path("state").asText())) return ListingStatus.ofPullRequest(pr, "", "", "");
        String conclusion = "";
        String refusal = "";
        String sha = pr.path("head").path("sha").asText("");
        if (!sha.isBlank()) {
            JsonNode runs = client.get(catalogApi() + "/commits/" + sha + "/check-runs?check_name="
                    + ListingStatus.CHECK_NAME, token).join();
            if (runs != null && !runs.path("check_runs").isEmpty()) {
                JsonNode run = runs.path("check_runs").get(0);
                conclusion = run.path("conclusion").asText("");
                if ("failure".equals(conclusion)) refusal = refusalOf(client.get(catalogApi() + "/check-runs/"
                        + run.path("id").asLong() + "/annotations", token).join());
            }
        }
        String comment = "";
        JsonNode comments = client.get(catalogApi() + "/issues/" + pr.path("number").asInt()
                + "/comments?per_page=100", token).join();
        if (comments != null) {
            for (JsonNode c : comments) {
                String text = c.path("body").asText("");
                if (text.startsWith(ListingStatus.MARKER)) comment = text;
            }
        }
        return ListingStatus.ofPullRequest(pr, conclusion, comment, refusal);
    }

    /** The check's own words from a failed run's annotations, without the runner's "exit code" line. */
    static String refusalOf(JsonNode annotations) {
        if (annotations == null) return "";
        StringBuilder out = new StringBuilder();
        for (JsonNode a : annotations) {
            String message = a.path("message").asText("").strip();
            if (message.isBlank() || message.startsWith("Process completed with exit code")) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(message);
        }
        return out.toString();
    }

    /** The sha of a file on {@code branch}, or null when it is not there. */
    private String fileSha(String contentsUrl, String branch, String token) {
        JsonNode file = client.get(contentsUrl + "?ref=" + branch, token).join();
        return file == null ? null : file.path("sha").asText(null);
    }

    private static String contentsUrl(String repoApi, String path) {
        return repoApi + "/contents/" + path;
    }

    private static String catalogApi() {
        return GitHubConfig.API_BASE + "/repos/" + GitHubConfig.CATALOG_OWNER + "/" + GitHubConfig.CATALOG_REPO;
    }

    /** Sleeps; false when interrupted, with the flag restored. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
