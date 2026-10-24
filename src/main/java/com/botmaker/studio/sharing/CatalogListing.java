package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.github.GitHubError;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * Studio's transport for the catalog's pull requests, through GitHub's API with Studio's sign-in, and what the
 * catalog says of a bot's entry (2026-10-23). What a pull request writes, and every rule it is held to, are the cli's
 * ({@link CatalogPr.Change}, decided by {@code com.botmaker.cli.publish.Publish}, 2026-10-24); this only carries
 * one, the way the cli's {@code CatalogPr.open} carries it through {@code gh}. Nobody reviews it: the catalog's
 * {@code validate} check runs on it and {@code automerge.yml} merges it when the check passes.
 *
 * <p>An account that can push to the catalog gets a branch there; everyone else a fork, and a branch of the fork.
 * The branch is the change's own ({@link CatalogPr.Change#branch}), reset to the catalog's {@code main} each time: a
 * fork's {@code main} diverges at the first squash-merge, so a second pull request from it would carry the first
 * entry again, and a branch per entry carries exactly one file's change, its open pull request updated in place.
 *
 * <p>Blocking; off the FX thread.
 */
public final class CatalogListing {

    private final GitHubClient client;

    public CatalogListing(GitHubClient client) {
        this.client = client;
    }

    /** {@code bots/<owner>__<name>.json}, the entry path of the bot at {@code owner/name}. */
    public static String path(String repo) {
        return EntryPath.bot(repo);
    }

    /** {@code entry/<path>}: one branch per entry, as the cli names it. */
    static String branch(String path) {
        return "entry/" + path;
    }

    /**
     * Whether the catalog's {@code main} holds the bot's entry. Fails when GitHub cannot say: only its 404 means "not
     * listed", never a rate limit or an outage.
     */
    public boolean listed(String repo, String token) throws IOException {
        return textOnMain(path(repo), token).isPresent();
    }

    /**
     * Opens, or updates, {@code change}'s pull request as {@code login}. {@link ListingStatus.State#UNCHANGED} when
     * {@code main} already reads so ({@link ListingStatus.State#NOT_LISTED} for a removal of nothing); an open pull
     * request that would change it is then closed.
     */
    public ListingStatus open(CatalogPr.Change change, String login, String token) throws IOException {
        String path = change.path().path();
        Optional<String> main = textOnMain(path, token);
        Optional<JsonNode> pr = openPullRequest(change.branch(), login, token);
        if (main.equals(change.content())) {
            close(pr, token);
            return ListingStatus.of(main.isPresent() ? ListingStatus.State.UNCHANGED : ListingStatus.State.NOT_LISTED);
        }
        if (pr.isPresent() && textAt(pr.get(), path, token).equals(change.content())) return statusOf(pr.get(), token);
        return push(change, login, token);
    }

    /** {@code login}'s open pull request from {@code branch}, the branch {@link #push} uses. */
    private Optional<JsonNode> openPullRequest(String branch, String login, String token) throws IOException {
        JsonNode open = read(catalogApi() + "/pulls?state=open&head=" + headOwner(login, token) + ":" + branch,
                token).orElse(null);
        return open != null && open.isArray() && !open.isEmpty() ? Optional.of(open.get(0)) : Optional.empty();
    }

    /** The entry file's text on the catalog's {@code main}, or empty when there is none. */
    private Optional<String> textOnMain(String path, String token) throws IOException {
        return text(read(contentsUrl(catalogApi(), path) + "?ref=" + GitHubConfig.CATALOG_BRANCH, token));
    }

    /** The entry file's text on an open pull request's head branch; empty when the branch removes it. */
    private Optional<String> textAt(JsonNode pr, String path, String token) throws IOException {
        String head = pr.path("head").path("repo").path("full_name").asText("");
        if (head.isBlank()) return Optional.empty();
        return text(read(contentsUrl(GitHubConfig.API_BASE + "/repos/" + head, path) + "?ref="
                + pr.path("head").path("ref").asText(""), token));
    }

    /** A Contents API file's text. */
    static Optional<String> text(Optional<JsonNode> file) {
        return file.map(f -> new String(Base64.getDecoder().decode(f.path("content").asText("")
                .replaceAll("\\s", "")), StandardCharsets.UTF_8));
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
        boolean listed = listed(repo, token);
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

    // -------------------------------------------------------------------------
    // The pull request
    // -------------------------------------------------------------------------

    private ListingStatus push(CatalogPr.Change change, String login, String token) throws IOException {
        try {
            boolean push = canPush(token);
            String headOwner = push ? GitHubConfig.CATALOG_OWNER : login;
            String headApi = push ? catalogApi() : fork(login, token);
            String branch = change.branch();
            String path = change.path().path();
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
