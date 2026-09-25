package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Publish side of the federated gallery (requires authentication via {@link GitHubAuth}).
 *
 * <p>Publishing a bot is {@link PublishPlan.Step}'s five steps: ensure the author's GitHub repo exists and is
 * public and is the project's {@code mine}, tag the saved version and push it there (JGit, the token per call),
 * cut a release, check that the release downloads without signing in, and — when listed — submit the bot's
 * entry to the gallery. The repo and release are the source of truth for installs and updates; the listing only
 * adds the bot to discovery.
 *
 * <p><b>A publish is a {@link Run}, resumed rather than restarted.</b> Each step is written so that running
 * it again after a partial failure is safe — the repository is found rather than recreated, a release whose
 * tag exists is taken as cut, a listing identical to the gallery's is not submitted — so a retry continues
 * from the step that failed.
 *
 * <p><b>A listing is a pull request the gallery merges by itself.</b> The gallery's CI runs the same gate
 * {@code botmaker bot publish} runs, and merges a passing pull request unless the author is over the
 * new-listings limit or the change needs a maintainer. {@link ListingStatus} reads which of those happened.
 *
 * <p>Blocking — intended to run off the FX thread (the Versions tab's publish sheet wraps it in a background
 * task).
 */
public final class BotPublisher {

    private final GitHubClient client;
    private final GitHubAuth auth;

    public BotPublisher(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
    }

    /**
     * A publish of {@code request}, nothing run yet, pushing with {@code vcs} — whose {@code HEAD} is the version
     * being published, saved by the caller. Call {@link Run#resume} off the FX thread.
     */
    public Run start(PublishRequest request, ProjectVcs vcs) {
        return new Run(request, vcs);
    }

    /**
     * One publish, and everything its finished steps learnt: the account, the repository's URL and default
     * branch, the commit pushed. That is what makes a resumed step behave as if the ones before it had just run.
     *
     * <p>Confined to one thread at a time; the dialog never resumes a run that is already running.
     */
    public final class Run {

        private final PublishRequest request;
        private final ProjectVcs vcs;
        private PublishPlan plan;
        private ListingStatus listing = ListingStatus.of(ListingStatus.State.NOT_LISTED);

        private String login;
        private String token;
        private String repoApi;
        private String repoUrl;
        private String branch;
        private String commitSha;

        private Run(PublishRequest request, ProjectVcs vcs) {
            this.request = request;
            this.vcs = vcs;
            this.plan = PublishPlan.start(request.listed());
        }

        public PublishRequest request() {
            return request;
        }

        public PublishPlan plan() {
            return plan;
        }

        /** What the gallery did with the listing; {@code NOT_LISTED} until the listing step has run. */
        public ListingStatus listing() {
            return listing;
        }

        /** The repository's page, blank until the repository step has run. */
        public String repoUrl() {
            return repoUrl == null ? "" : repoUrl;
        }

        /**
         * Runs from the first step not yet done until the plan is finished or a step fails, reporting every
         * transition to {@code onChange} on the calling thread. Blocking.
         */
        public PublishPlan resume(Consumer<PublishPlan> onChange) {
            while (plan.next().isPresent()) {
                PublishPlan.Step step = plan.next().get();
                plan = plan.running(step);
                onChange.accept(plan);
                try {
                    plan = plan.done(step, perform(step));
                } catch (Exception e) {
                    plan = plan.failed(step, rootMessage(e));
                    onChange.accept(plan);
                    return plan;
                }
                onChange.accept(plan);
            }
            return plan;
        }

        private String perform(PublishPlan.Step step) throws Exception {
            return switch (step) {
                case REPO -> ensureRepository();
                case PUSH -> push();
                case RELEASE -> release();
                case ARCHIVE -> checkArchive();
                case LISTING -> submitListing();
            };
        }

        private void account() throws IOException {
            if (login != null) return;
            if (!auth.isAuthenticated()) {
                throw new IOException("Not signed in to GitHub.");
            }
            String name = auth.login(client).join();
            if (name == null || name.isBlank()) {
                throw new IOException("Could not read your GitHub account.");
            }
            token = auth.token();
            login = name;
            repoApi = GitHubConfig.API_BASE + "/repos/" + login + "/" + request.repoName();
        }

        /**
         * Step 1: the repository exists, and is public — a published bot is meant to be installable — and it is
         * the project's {@code mine}: a publish pushes the user's own versions, so it goes where
         * <i>Save to my copy</i> does ({@code docs/refactor/39-versions.md} §8).
         */
        private String ensureRepository() throws IOException {
            account();
            String url = BotInstaller.cloneUrl(login, request.repoName());
            String mine = vcs.remoteUrl(Remote.MINE);
            if (mine != null && !sameRepo(mine, url)) {
                throw new IOException("This project's copy is " + mine + ", not " + url + ". A publish goes to "
                        + "your own copy; publish under that repository's name, or use Save to my copy.");
            }
            // No auto-init: an empty repository takes the first push as it is, with no README commit to join.
            JsonNode repo = client.ensureRepo(login, request.repoName(), request.description(), false, false, token);
            if (mine == null) vcs.setRemote(Remote.MINE, url);

            // ensureRepo returns an existing repo as-is, and Save to my copy creates that repo private.
            // Publishing into it would cut a release nobody but the author can see — the gallery reads it back
            // as "no release yet" and the install fails. So widen it here, and never the reverse.
            boolean madePublic = false;
            if (repo.path("private").asBoolean(false)) {
                try {
                    repo = client.patch(repoApi, mapOf("private", false), token).join();
                    madePublic = true;
                } catch (Exception e) {
                    throw new IOException("Couldn't make " + login + "/" + request.repoName() + " public, so the "
                            + "release wouldn't be installable (" + rootMessage(e) + "). Change its visibility "
                            + "on github.com, or sign out and back in to refresh the token's permissions.", e);
                }
            }
            repoUrl = repo.path("html_url").asText("https://github.com/" + login + "/" + request.repoName());
            branch = repo.path("default_branch").asText("main");
            return madePublic ? repoUrl + " (made public)" : repoUrl;
        }

        /**
         * Step 2: the version is tagged and pushed to {@code mine} — the branch, the tags, the version names —
         * never forced. A repository Studio once published by snapshot has a history of its own, and is joined
         * first ({@link ProjectVcs#joinUnrelated}); one that is merely ahead refuses, and says so.
         */
        private String push() throws IOException {
            if (branch == null) ensureRepository();
            boolean joined = vcs.joinUnrelated(Remote.MINE, branch,
                    "Joined the versions already on github.com/" + login + "/" + request.repoName(), token);
            commitSha = vcs.tagHead(request.version(), "Published " + request.botName() + " " + request.version());
            vcs.push(Remote.MINE, branch, token);
            return commitSha.substring(0, 7) + (joined ? ", joined to the history on GitHub" : "");
        }

        /**
         * Step 3: the release. A tag that already exists is taken as this step having run — the ordinary case
         * for a retry whose release request reached GitHub and whose answer did not reach us.
         */
        private String release() throws IOException {
            if (branch == null) ensureRepository();
            String version = request.version();
            JsonNode existing = client.get(repoApi + "/releases/tags/" + version, token).join();
            if (existing == null) {
                client.post(repoApi + "/releases", mapOf(
                        "tag_name", version, "name", version,
                        "target_commitish", commitSha != null ? commitSha : branch,
                        "body", "Published from BotMaker Studio"), token).join();
            }
            // The provenance file is written by the caller, into the version this publishes — written here, it
            // would be an unsaved change the moment the publish ended.
            return existing == null ? version : version + " (already existed)";
        }

        /**
         * Step 4: the release downloads <em>without</em> a token, which is what somebody installing it has —
         * and what the gallery's gate checks before it lists anything. GitHub can take a moment to serve a new
         * tag's archive, so it is asked a few times before this is called a failure.
         */
        private String checkArchive() throws IOException {
            account();
            String url = GitHubConfig.archiveUrl(login, request.repoName(), request.version());
            Exception last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    byte[] zip = client.getBytes(url, null).join();
                    return (zip.length / 1024) + " KB";
                } catch (Exception e) {
                    last = e;
                    sleep(2000);
                }
            }
            throw new IOException("The release " + request.version() + " does not download without signing in ("
                    + rootMessage(last) + "). The gallery refuses a listing whose release cannot be installed.",
                    last);
        }

        /** Step 5: the topic signal, then the entry — see {@link #submit}. */
        private String submitListing() throws IOException {
            account();
            try {
                client.put(repoApi + "/topics", mapOf("names", List.of(GitHubConfig.TOPIC)), token).join();
            } catch (Exception ignored) {
                // A secondary signal only; the gallery is authoritative.
            }
            listing = submit(login, token, request.repoName(), request.entry(login));
            return listing.describe();
        }
    }

    // -------------------------------------------------------------------------
    // Listing (one entry file, through a pull request on a branch of its own)
    // -------------------------------------------------------------------------

    /**
     * Delists a bot: removes its entry file from the gallery, by pull request. The author's repository and
     * releases are left intact — this only removes the bot from discovery. Blocking; run off the FX thread.
     */
    public ListingStatus unpublish(String repoName) throws IOException {
        if (!auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        String login = auth.login(client).join();
        if (login == null || login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        return submit(login, auth.token(), repoName, null);
    }

    /**
     * What the gallery currently says about {@code login/repoName}'s listing: the newest listing pull request
     * from this Studio's branch for it, read through its check run, labels and the gallery's comment, or — with
     * none — whether the entry file exists. Blocking; run off the FX thread.
     */
    public ListingStatus listingStatus(String repoName) throws IOException {
        if (!auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        String login = auth.login(client).join();
        if (login == null || login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        String token = auth.token();
        boolean inGallery = client.get(upstreamApi() + "/contents/" + GitHubConfig.entryPath(login, repoName)
                + "?ref=" + GitHubConfig.INDEX_BRANCH, token).join() != null;
        if (!login.equalsIgnoreCase(GitHubConfig.MAINTAINER)) {
            JsonNode prs = client.get(upstreamApi() + "/pulls?state=all&per_page=1&head=" + login + ":"
                    + listingBranch(repoName), token).join();
            if (prs != null && prs.isArray() && !prs.isEmpty()) {
                ListingStatus status = statusOf(prs.get(0), token);
                // The newest pull request may have been a removal; what is in the gallery settles it.
                if (status.state() == ListingStatus.State.LISTED && !inGallery) {
                    return ListingStatus.of(ListingStatus.State.NOT_LISTED);
                }
                if (status.state() != ListingStatus.State.CLOSED) return status;
            }
        }
        return ListingStatus.of(inGallery ? ListingStatus.State.LISTED : ListingStatus.State.NOT_LISTED);
    }

    /**
     * Writes ({@code entry} non-null) or removes ({@code entry} null) {@code bots/<login>-<repoName>.json}.
     *
     * <p>The maintainer commits directly, since they cannot fork their own repository. The comparison is
     * against {@link GitHubConfig#MAINTAINER} — the person — rather than the gallery's owner, which is an
     * organization since 2026-09-18 and equals nobody's login; against the owner, the maintainer would take
     * the fork path and GitHub would refuse the fork. Everyone
     * else gets a fork, a branch of that fork named for the bot and started from the gallery's current tip,
     * and one pull request from it. The branch is why re-publishing works: a fork's {@code main} diverges
     * from the gallery the first time a listing is squash-merged, so a second pull request from it would carry
     * the first listing again. A branch reset to the gallery's tip carries exactly one file's change, and an
     * open pull request from it is updated in place rather than duplicated.
     */
    private ListingStatus submit(String login, String token, String repoName, Map<String, Object> entry)
            throws IOException {
        if (!GitHubConfig.isGalleryConfigured()) {
            throw new IOException("The gallery is not configured in this build.");
        }
        String path = GitHubConfig.entryPath(login, repoName);
        JsonNode current = client.get(upstreamApi() + "/contents/" + path + "?ref=" + GitHubConfig.INDEX_BRANCH,
                token).join();
        if (entry != null && current != null && sameEntry(client.mapper(), current, entry)) {
            return ListingStatus.of(ListingStatus.State.UNCHANGED);
        }
        if (entry == null && current == null) {
            return ListingStatus.of(ListingStatus.State.NOT_LISTED);
        }

        try {
            if (login.equalsIgnoreCase(GitHubConfig.MAINTAINER)) {
                if (entry != null) {
                    writeEntry(GitHubConfig.INDEX_OWNER, GitHubConfig.INDEX_BRANCH, token, entry, login, repoName);
                } else {
                    deleteEntry(GitHubConfig.INDEX_OWNER, GitHubConfig.INDEX_BRANCH, token, login, repoName);
                }
                return ListingStatus.of(entry != null ? ListingStatus.State.COMMITTED
                        : ListingStatus.State.NOT_LISTED);
            }

            client.post(upstreamApi() + "/forks", Map.of(), token).join();
            if (!awaitFork(login, token)) {
                throw new IOException("Couldn't reach your fork of the gallery yet — retry in a minute.");
            }
            String branch = listingBranch(repoName);
            prepareBranch(login, branch, token);
            if (entry != null) {
                writeEntry(login, branch, token, entry, login, repoName);
            } else {
                deleteEntry(login, branch, token, login, repoName);
            }

            JsonNode open = client.get(upstreamApi() + "/pulls?state=open&head=" + login + ":" + branch, token)
                    .join();
            if (open != null && open.isArray() && !open.isEmpty()) {
                return statusOf(open.get(0), token);
            }
            String slug = login + "/" + repoName;
            JsonNode pr = client.post(upstreamApi() + "/pulls", mapOf(
                    "title", (entry != null ? (current == null ? "Add " : "Update ") : "Remove ") + slug,
                    "head", login + ":" + branch,
                    "base", GitHubConfig.INDEX_BRANCH,
                    "body", entry != null
                            ? "Submitted from BotMaker Studio. The gallery's checks run on this pull request, "
                                    + "and it merges by itself when they pass."
                            : "Unpublished from BotMaker Studio."), token).join();
            return ListingStatus.ofPullRequest(pr, "", "");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("The gallery submission failed: " + rootMessage(e), e);
        }
    }

    /** The branch of the author's fork that carries one bot's listing. */
    static String listingBranch(String repoName) {
        return "listing/" + repoName;
    }

    /**
     * Points {@code branch} of the author's fork at the gallery's current tip, creating it if needed.
     *
     * <p>A fork shares its network's objects, so a ref may name a commit only the gallery has. Should GitHub
     * refuse that anyway, the fork's {@code main} is synced first and the branch starts there.
     */
    private void prepareBranch(String login, String branch, String token) {
        String forkApi = GitHubConfig.API_BASE + "/repos/" + login + "/" + GitHubConfig.INDEX_REPO;
        JsonNode tip = client.get(upstreamApi() + "/git/refs/heads/" + GitHubConfig.INDEX_BRANCH, token).join();
        String sha = tip == null ? null : tip.path("object").path("sha").asText(null);
        try {
            pointBranch(forkApi, branch, sha, token);
        } catch (Exception refused) {
            client.post(forkApi + "/merge-upstream", mapOf("branch", GitHubConfig.INDEX_BRANCH), token).join();
            JsonNode forkMain = client.get(forkApi + "/git/refs/heads/" + GitHubConfig.INDEX_BRANCH, token).join();
            pointBranch(forkApi, branch, forkMain.path("object").path("sha").asText(), token);
        }
    }

    private void pointBranch(String forkApi, String branch, String sha, String token) {
        if (sha == null) throw new IllegalStateException("The gallery's " + GitHubConfig.INDEX_BRANCH
                + " branch could not be read.");
        String refUrl = forkApi + "/git/refs/heads/" + branch;
        if (client.get(refUrl, token).join() != null) {
            client.patch(refUrl, mapOf("sha", sha, "force", true), token).join();
        } else {
            client.post(forkApi + "/git/refs", mapOf("ref", "refs/heads/" + branch, "sha", sha), token).join();
        }
    }

    /** Reads one listing pull request: its {@code validate} run and the gallery's comment, when it is open. */
    private ListingStatus statusOf(JsonNode pr, String token) {
        if (!"open".equals(pr.path("state").asText())) {
            return ListingStatus.ofPullRequest(pr, "", "");
        }
        String conclusion = "";
        String sha = pr.path("head").path("sha").asText("");
        if (!sha.isBlank()) {
            JsonNode runs = client.get(upstreamApi() + "/commits/" + sha + "/check-runs?check_name="
                    + ListingStatus.CHECK_NAME, token).join();
            if (runs != null && runs.path("check_runs").size() > 0) {
                JsonNode run = runs.path("check_runs").get(0);
                conclusion = run.hasNonNull("conclusion") ? run.get("conclusion").asText("") : "";
            }
        }
        String comment = "";
        JsonNode comments = client.get(upstreamApi() + "/issues/" + pr.path("number").asInt()
                + "/comments?per_page=100", token).join();
        if (comments != null) {
            for (JsonNode c : comments) {
                String body = c.path("body").asText("");
                if (body.startsWith(ListingStatus.MARKER)) comment = body;
            }
        }
        return ListingStatus.ofPullRequest(pr, conclusion, comment);
    }

    /** True when the Contents API's {@code current} file already holds {@code entry}, compared as JSON. */
    static boolean sameEntry(ObjectMapper mapper, JsonNode current, Map<String, Object> entry) {
        try {
            String encoded = current.path("content").asText("").replaceAll("\\s", "");
            JsonNode existing = mapper.readTree(Base64.getDecoder().decode(encoded));
            return existing.equals(mapper.valueToTree(entry));
        } catch (Exception unreadable) {
            return false;
        }
    }

    private static String upstreamApi() {
        return GitHubConfig.API_BASE + "/repos/" + GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO;
    }

    // Community patching (submitPatch: a snapshot tree force-pushed to the fork's editor-<login> branch) and
    // syncFork (GitHub's merge-upstream on the fork, never the local project) were deleted on 2026-09-25: an
    // installed bot is a clone now, so Suggestion pushes real versions and Get vX.Y is a local merge
    // (docs/refactor/39-versions.md §7). The publish's own snapshot push (blobs, tree, a commit built on GitHub
    // and the branch forced to it) went the same day: a publish pushes the project's versions to `mine`.

    /** True when two GitHub URLs name one repository, whatever their case or {@code .git} suffix. */
    static boolean sameRepo(String a, String b) {
        var x = SyncModel.slug(a);
        var y = SyncModel.slug(b);
        return x.isPresent() && y.isPresent() && x.get().toString().equalsIgnoreCase(y.get().toString());
    }

    /**
     * Deletes {@code bots/<owner>-<repo>.json} from {@code branch} of {@code repoOwner}'s copy of the gallery.
     *
     * <p><b>A deletion, which is the half of this layout worth having.</b> Delisting used to rewrite the
     * whole array, so the diff a maintainer had to review was the entire gallery and the one line that
     * mattered was somewhere inside it.
     */
    private void deleteEntry(String repoOwner, String branch, String token, String owner, String repoName)
            throws IOException {
        String path = GitHubConfig.entryPath(owner, repoName);
        String contentsUrl = GitHubConfig.API_BASE + "/repos/" + repoOwner + "/" + GitHubConfig.INDEX_REPO
                + "/contents/" + path;

        String sha = fileSha(contentsUrl, branch, token);
        if (sha == null) {
            throw new IOException("No " + path + " in " + repoOwner + "/" + GitHubConfig.INDEX_REPO
                    + " — nothing to remove.");
        }
        client.delete(contentsUrl, mapOf(
                "message", "Remove " + owner + "/" + repoName + " from the gallery",
                "sha", sha,
                "branch", branch), token).join();
    }

    /**
     * Writes {@code bots/<owner>-<repo>.json} into {@code branch} of {@code repoOwner}'s copy of the gallery.
     *
     * <p><b>One file, and never {@code index.json} or {@code catalog.json}</b> — both are generated by the
     * gallery's own CI and a pull request editing either is refused. Re-publishing is idempotent by
     * construction: the entry's path is its identity, so a second publish replaces the author's own file and
     * touches no line of anybody else's.
     */
    private void writeEntry(String repoOwner, String branch, String token, Map<String, Object> entry, String owner,
                            String repoName) throws IOException {
        String path = GitHubConfig.entryPath(owner, repoName);
        String contentsUrl = GitHubConfig.API_BASE + "/repos/" + repoOwner + "/" + GitHubConfig.INDEX_REPO
                + "/contents/" + path;

        Map<String, Object> body = mapOf(
                "message", "List " + owner + "/" + repoName + " in the gallery",
                "content", Base64.getEncoder().encodeToString(entryJson(client.mapper(), entry)),
                "branch", branch);
        // An UPDATE needs the file's current sha and a CREATE must not carry one, so the absence of the file
        // is a normal outcome here rather than an error.
        String sha = fileSha(contentsUrl, branch, token);
        if (sha != null) {
            body.put("sha", sha);
        }
        client.put(contentsUrl, body, token).join();
    }

    /** One entry as the bytes of its own file: pretty-printed, newline-terminated. */
    static byte[] entryJson(ObjectMapper mapper, Map<String, Object> entry) throws IOException {
        return (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entry) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The sha of a file on {@code branch} of a repo, or null when it is not there. */
    private String fileSha(String contentsUrl, String branch, String token) {
        try {
            JsonNode contents = client.get(contentsUrl + "?ref=" + branch, token).join();
            return contents == null ? null : contents.path("sha").asText(null);
        } catch (Exception absent) {
            return null;
        }
    }

    /**
     * Polls until the signed-in user's fork of the gallery is readable (fork creation is asynchronous).
     *
     * <p>Still {@code index.json} rather than the entries directory: it is the one file the gallery always
     * has, generated or not, and an empty {@code bots/} would answer 404 forever.
     */
    private boolean awaitFork(String login, String token) {
        String url = GitHubConfig.API_BASE + "/repos/" + login + "/" + GitHubConfig.INDEX_REPO
                + "/contents/" + GitHubConfig.INDEX_PATH + "?ref=" + GitHubConfig.INDEX_BRANCH;
        for (int attempt = 0; attempt < 10; attempt++) {
            JsonNode n = client.get(url, token).join();
            if (n != null && n.hasNonNull("content")) return true;
            if (!sleep(1500)) break;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
