package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Publish side of Browse Bots (requires authentication via {@link GitHubAuth}).
 *
 * <p>Publishing a bot is {@link PublishPlan.Step}'s five steps: ensure the author's GitHub repo exists and is
 * public and is the project's {@code mine}, tag the saved version and push it there (JGit, the token per call),
 * cut a release, check that the release downloads without signing in, and — when listed — give the repository
 * the topic {@link GitHubConfig#TOPIC}, its description and its tags. The repository is the whole listing
 * (2026-10-23): Browse Bots searches GitHub for the topic ({@link GitHubGallery#browse}), so there is no gallery
 * to submit to, nothing to wait for, and unpublishing is the topic taken off.
 *
 * <p><b>A publish is a {@link Run}, resumed rather than restarted.</b> Each step is written so that running
 * it again after a partial failure is safe — the repository is found rather than recreated, a release whose
 * tag exists is taken as cut, a topic already there is kept — so a retry continues from the step that failed.
 *
 * <p>Blocking — intended to run off the FX thread (the Versions tab's publish sheet wraps it in a background
 * task).
 */
public final class BotPublisher {

    /** GitHub's most topics on one repository. */
    static final int MAX_TOPICS = 20;
    /** GitHub's longest topic. */
    private static final int MAX_TOPIC_LENGTH = 50;

    private final GitHubClient client;
    private final GitHubAuth auth;

    public BotPublisher(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
    }

    /**
     * A publish of {@code request}, nothing run yet, pushing with {@code vcs} — whose {@code HEAD} is the version
     * being published, saved by the caller — to {@code owner/<repo>}: the signed-in account, or an owner the
     * project's copy already lives under and the account can push to ({@link #ownerFor}). Call
     * {@link Run#resume} off the FX thread.
     */
    public Run start(PublishRequest request, ProjectVcs vcs, String owner) {
        return new Run(request, vcs, owner);
    }

    /**
     * Whose repository a publish goes to: the owner of the project's copy ({@code mine}) when the signed-in
     * account may push there — an organization's template, a repository shared with the user — else the
     * account itself, which is where a new copy is made. Comparing case-blind, as GitHub does.
     */
    public static String ownerFor(String login, Optional<SyncModel.Slug> mine, boolean canPushToMine) {
        if (mine.isEmpty() || mine.get().owner().equalsIgnoreCase(login)) return login;
        return canPushToMine ? mine.get().owner() : login;
    }

    /**
     * Whether the signed-in account may push to {@code owner/repo}: the repository's {@code permissions.push}
     * as GitHub reports it to that account. False when it cannot be read. Blocking; off the FX thread.
     */
    public boolean canPush(String owner, String repo) {
        if (!auth.isAuthenticated()) return false;
        try {
            JsonNode found = client.get(GitHubConfig.API_BASE + "/repos/" + owner + "/" + repo, auth.token()).join();
            return found != null && found.path("permissions").path("push").asBoolean(false);
        } catch (Exception unreadable) {
            return false;
        }
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
        /** Whose repository this publishes: the account, or an owner it can push to. */
        private final String owner;
        private PublishPlan plan;

        private String login;
        private String token;
        private String repoApi;
        private String repoUrl;
        private String branch;
        private String commitSha;

        private Run(PublishRequest request, ProjectVcs vcs, String owner) {
            this.request = request;
            this.vcs = vcs;
            this.owner = owner;
            this.plan = PublishPlan.start(request.listed());
        }

        /** Whose repository this publishes. */
        public String owner() {
            return owner;
        }

        public PublishRequest request() {
            return request;
        }

        public PublishPlan plan() {
            return plan;
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
                case LISTING -> list();
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
            repoApi = repoApi(owner, request.repoName());
        }

        private String slug() {
            return owner + "/" + request.repoName();
        }

        /**
         * Step 1: the repository exists, and is public — a published bot is meant to be installable — and it is
         * the project's {@code mine}: a publish pushes the user's own versions, so it goes where
         * <i>Save to my copy</i> does ({@code docs/refactor/39-versions.md} §8).
         */
        private String ensureRepository() throws IOException {
            account();
            String url = BotInstaller.cloneUrl(owner, request.repoName());
            String mine = vcs.remoteUrl(Remote.MINE);
            if (mine != null && !sameRepo(mine, url)) {
                throw new IOException("This project's copy is " + mine + ", not " + url + ". A publish goes to "
                        + "your own copy; publish under that repository's name, or use Save to my copy.");
            }
            JsonNode repo;
            if (owner.equalsIgnoreCase(login)) {
                // No auto-init: an empty repository takes the first push as it is, with no README commit to join.
                repo = client.ensureRepo(login, request.repoName(), request.description(), false, false, token);
            } else {
                // Another owner's repository is published into, never made: Studio creates only the user's own.
                repo = client.get(repoApi, token).join();
                if (repo == null) {
                    throw new IOException("github.com/" + slug() + " does not exist, and Studio makes repositories "
                            + "only on your own account.");
                }
            }
            if (mine == null) vcs.setRemote(Remote.MINE, url);

            // ensureRepo returns an existing repo as-is, and Save to my copy creates that repo private.
            // Publishing into it would cut a release nobody but the author can see — Browse Bots reads it back
            // as "no release yet" and the install fails. So widen it here, and never the reverse.
            boolean madePublic = false;
            if (repo.path("private").asBoolean(false)) {
                try {
                    repo = client.patch(repoApi, mapOf("private", false), token).join();
                    madePublic = true;
                } catch (Exception e) {
                    throw new IOException("Couldn't make " + slug() + " public, so the "
                            + "release wouldn't be installable (" + rootMessage(e) + "). Change its visibility "
                            + "on github.com, or sign out and back in to refresh the token's permissions.", e);
                }
            }
            repoUrl = repo.path("html_url").asText("https://github.com/" + slug());
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
                    "Joined the versions already on github.com/" + slug(), token);
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
            return existing == null ? version : version + " (already existed)";
        }

        /**
         * Step 4: the release downloads <em>without</em> a token, which is what somebody installing it has.
         * GitHub can take a moment to serve a new tag's archive, so it is asked a few times before this is called
         * a failure.
         */
        private String checkArchive() throws IOException {
            account();
            String url = GitHubConfig.archiveUrl(owner, request.repoName(), request.version());
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
                    + rootMessage(last) + "), so nobody else could install it.", last);
        }

        /**
         * Step 5: the repository's description, and its topics with {@link GitHubConfig#TOPIC} and the bot's tags
         * added — the ones already there are kept ({@link #topics}).
         */
        private String list() throws IOException {
            account();
            try {
                if (!request.description().isBlank()) {
                    client.patch(repoApi, mapOf("description", request.description()), token).join();
                }
                client.put(repoApi + "/topics", mapOf("names",
                        topics(currentTopics(repoApi, token), request.tags(), true)), token).join();
            } catch (Exception e) {
                throw new IOException("Couldn't set the topic " + GitHubConfig.TOPIC + " on " + slug() + ": "
                        + rootMessage(e), e);
            }
            return "Listed. Browse Bots finds it within a few minutes, once GitHub's search has caught up.";
        }
    }

    // -------------------------------------------------------------------------
    // The listing: the repository's own topics (2026-10-23)
    // -------------------------------------------------------------------------

    // The gallery's listing pull request (submit, ListingStatus, an entry file per bot on a branch of the
    // author's fork of the gallery) went on 2026-10-23 with the gallery repository: the topic is the listing.

    /**
     * What GitHub says of {@code owner/repoName} as a listing: whether it has the topic, and the description and
     * tags a re-publish starts from.
     *
     * @param listed      whether the repository carries {@link GitHubConfig#TOPIC}
     * @param description the repository's description, blank when it has none
     * @param tags        its topics, Studio's own ({@code botmaker-…}) left out
     */
    public record Listing(boolean listed, String description, List<String> tags) {

        public static final Listing NONE = new Listing(false, "", List.of());

        public Listing {
            description = description == null ? "" : description;
            tags = List.copyOf(tags);
        }

        /** Reads a repository object; {@link #NONE} when there is none. */
        static Listing of(JsonNode repo) {
            if (repo == null) return NONE;
            boolean listed = false;
            List<String> tags = new ArrayList<>();
            for (JsonNode topic : repo.path("topics")) {
                String t = topic.asText("");
                if (t.equals(GitHubConfig.TOPIC)) listed = true;
                else if (!t.isBlank() && !t.startsWith("botmaker-")) tags.add(t);
            }
            return new Listing(listed, repo.path("description").asText(""), tags);
        }

        /** One line for the sheet. */
        public String describe() {
            return listed ? "Listed in Browse Bots." : "Not listed in Browse Bots.";
        }
    }

    /** {@code owner/repoName} as a listing. Blocking; run off the FX thread. */
    public Listing listing(String owner, String repoName) {
        String token = auth != null && auth.isAuthenticated() ? auth.token() : null;
        try {
            return Listing.of(client.get(repoApi(owner, repoName), token).join());
        } catch (Exception unreadable) {
            return Listing.NONE;
        }
    }

    /**
     * Delists a bot: takes {@link GitHubConfig#TOPIC} off its repository, every other topic kept. The repository
     * and its releases are left intact, and whoever started from it stays linked. Blocking; run off the FX thread.
     */
    public void unpublish(String owner, String repoName) throws IOException {
        if (!auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        String api = repoApi(owner, repoName);
        try {
            client.put(api + "/topics", mapOf("names",
                    topics(currentTopics(api, auth.token()), List.of(), false)), auth.token()).join();
        } catch (Exception e) {
            throw new IOException("Couldn't take the topic " + GitHubConfig.TOPIC + " off " + owner + "/" + repoName
                    + ": " + rootMessage(e), e);
        }
    }

    /**
     * The repository's topics now. Fails when they cannot be read: the PUT that follows replaces them all, so an
     * unread list would wipe every topic the author set.
     */
    private List<String> currentTopics(String repoApi, String token) {
        JsonNode body = client.getOrFail(repoApi + "/topics", token).join();
        List<String> names = new ArrayList<>();
        body.path("names").forEach(n -> names.add(n.asText("")));
        return names;
    }

    /**
     * The topics a repository should carry: {@code current} with the bot's {@code tags} (as topics) and
     * {@link GitHubConfig#TOPIC} added when {@code listed}, or {@link GitHubConfig#TOPIC} taken out when not. A
     * topic is never removed otherwise, so what an author set on github.com stays — except the last of twenty,
     * which makes room for the listing's: at most {@link #MAX_TOPICS}, the listing's own topic first.
     */
    static List<String> topics(List<String> current, List<String> tags, boolean listed) {
        Set<String> out = new LinkedHashSet<>();
        if (listed) out.add(GitHubConfig.TOPIC);
        for (String t : current) {
            if (!t.isBlank() && (listed || !t.equals(GitHubConfig.TOPIC)) && out.size() < MAX_TOPICS) out.add(t);
        }
        if (listed) {
            for (String tag : tags) {
                String topic = asTopic(tag);
                if (!topic.isEmpty() && !topic.startsWith("botmaker-") && out.size() < MAX_TOPICS) out.add(topic);
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code tag} as GitHub takes a topic: lower case, letters, digits and hyphens, a space or {@code _} a hyphen,
     * at most 50 characters. Blank when nothing is left.
     */
    static String asTopic(String tag) {
        String t = tag.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-").replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return t.length() > MAX_TOPIC_LENGTH ? t.substring(0, MAX_TOPIC_LENGTH).replaceAll("-$", "") : t;
    }

    private static String repoApi(String owner, String repoName) {
        return GitHubConfig.API_BASE + "/repos/" + owner + "/" + repoName;
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
