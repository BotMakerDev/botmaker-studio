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
 * its description and its tags (as topics, which the catalog shows) and open the catalog's pull request for its
 * entry ({@link CatalogListing}, 2026-10-23). The catalog's check merges it; nobody reviews it. Unpublishing and
 * <i>Stop following</i> are pull requests too: the entry removed, or marked {@code detached}.
 *
 * <p><b>A publish is a {@link Run}, resumed rather than restarted.</b> Each step is written so that running
 * it again after a partial failure is safe — the repository is found rather than recreated, a release whose
 * tag exists is taken as cut, the entry's open pull request is updated rather than duplicated — so a retry
 * continues from the step that failed.
 *
 * <p>Blocking — intended to run off the FX thread (the Versions tab's publish sheet wraps it in a background
 * task).
 */
public final class BotPublisher {

    // DETACHED_TOPIC (botmaker-detached) went on 2026-10-23: a fork that stopped following is an entry's
    // "detached": true, set by pull request (setDetached).

    /** GitHub's most topics on one repository. */
    static final int MAX_TOPICS = 20;
    /** GitHub's longest topic. */
    private static final int MAX_TOPIC_LENGTH = 50;

    private final GitHubClient client;
    private final GitHubAuth auth;
    private final CatalogListing catalog;

    public BotPublisher(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
        this.catalog = new CatalogListing(client);
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
     * Whether publishing into {@code owner}'s repository publishes a fork: the project follows an original under
     * another account ({@code 39} §8, 2026-10-23).
     */
    static boolean isFork(ProjectVcs vcs, String owner) {
        return SyncModel.slug(vcs.remoteUrl(Remote.ORIGINAL))
                .filter(original -> !original.owner().equalsIgnoreCase(owner)).isPresent();
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
        /** {@code owner/name} as GitHub spells it, which is what the catalog's entry names. */
        private String fullName;
        private String branch;
        private String commitSha;
        private ListingStatus listing = ListingStatus.of(ListingStatus.State.NOT_LISTED);

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

        /** What the catalog said when the listing step last ran. */
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
            fullName = repo.path("full_name").asText(slug());
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
            // A fork's main keeps tracking its author; the user's line is studio/<branch>, as Save to my copy
            // pushes it, and the release tags that line's head (2026-10-23). A fork shares its author's history,
            // so there is nothing to join.
            boolean fork = isFork(vcs, owner);
            // A fork that stopped following keeps its line where it pushed it as one.
            boolean beside = fork || vcs.hasRemoteBranch(Remote.MINE, "studio/" + vcs.branch());
            String target = beside ? "studio/" + vcs.branch() : branch;
            if (fork && vcs.hasTag(request.version()) && !vcs.isOwnRelease(request.version())) {
                // The author's release by that number is here already: marking it the fork's would hide it.
                throw new IOException("The original already has a release " + request.version()
                        + ". Pick a higher version for your copy.");
            }
            boolean joined = !beside && vcs.joinUnrelated(Remote.MINE, branch,
                    "Joined the versions already on github.com/" + slug(), token);
            commitSha = vcs.tagHead(request.version(), "Published " + request.botName() + " " + request.version());
            if (fork) vcs.markOwnRelease(request.version());
            vcs.push(Remote.MINE, target, token);
            return commitSha.substring(0, 7) + (beside ? " on " + target : "")
                    + (joined ? ", joined to the history on GitHub" : "");
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
         * Step 5: what Studio can check of the catalog's rules ({@link CatalogListing#refusal}), then the
         * repository's description and its tags as topics (the catalog shows a bot's topics as its tags; the ones
         * already there are kept, {@link #topics}), then the catalog's pull request for the entry. A fork that
         * stopped following keeps {@code detached} through a re-publish, even while that pull request is open.
         */
        private String list() throws IOException {
            if (fullName == null) ensureRepository();
            JsonNode release = client.get(repoApi + "/releases/tags/" + request.version(), token).join();
            Optional<String> refused = CatalogListing.refusal(release, login, owner);
            if (refused.isPresent()) throw new IOException(refused.get());
            try {
                if (!request.description().isBlank()) {
                    client.patch(repoApi, mapOf("description", request.description()), token).join();
                }
                List<String> tagged = topics(currentTopics(repoApi, token), request.tags());
                client.put(repoApi + "/topics", mapOf("names", tagged), token).join();
            } catch (Exception e) {
                throw new IOException("Couldn't set the description and tags of " + slug() + ": "
                        + rootMessage(e), e);
            }
            boolean detached = catalog.pending(fullName, login, token).map(CatalogListing.BotEntry::detached)
                    .orElse(false);
            listing = catalog.put(new CatalogListing.BotEntry(fullName, request.version(), detached), login, token);
            return listing.describe();
        }
    }

    // -------------------------------------------------------------------------
    // The listing: the catalog's entry, by pull request (2026-10-23)
    // -------------------------------------------------------------------------

    /**
     * What a re-publish starts from and what the catalog says: the repository's description and tags, and the
     * entry's state.
     *
     * @param status      the catalog's entry and its newest pull request
     * @param description the repository's description, blank when it has none
     * @param tags        its topics, Studio's own ({@code botmaker-…}) left out
     */
    public record Listing(ListingStatus status, String description, List<String> tags) {

        public static final Listing NONE = new Listing(ListingStatus.of(ListingStatus.State.NOT_LISTED), "",
                List.of());

        public Listing {
            description = description == null ? "" : description;
            tags = List.copyOf(tags);
        }

        /** Reads a repository object beside the catalog's {@code status}; {@link #NONE} when there is no repository. */
        static Listing of(JsonNode repo, ListingStatus status) {
            if (repo == null) return NONE;
            List<String> tags = new ArrayList<>();
            for (JsonNode topic : repo.path("topics")) {
                String t = topic.asText("");
                if (!t.isBlank() && !t.startsWith("botmaker-")) tags.add(t);
            }
            return new Listing(status, repo.path("description").asText(""), tags);
        }

        /** Whether the catalog's {@code main} holds the entry. */
        public boolean listed() {
            return status.state() == ListingStatus.State.LISTED;
        }
    }

    /** {@code owner/repoName} as a listing. Blocking; run off the FX thread. */
    public Listing listing(String owner, String repoName) {
        String token = auth != null && auth.isAuthenticated() ? auth.token() : null;
        try {
            JsonNode repo = client.get(repoApi(owner, repoName), token).join();
            if (repo == null) return Listing.NONE;
            String login = token == null ? "" : auth.login(client).join();
            String fullName = repo.path("full_name").asText(owner + "/" + repoName);
            ListingStatus status = login == null || login.isBlank()
                    ? catalog.listed(fullName, token).map(e -> ListingStatus.of(ListingStatus.State.LISTED))
                            .orElse(ListingStatus.of(ListingStatus.State.NOT_LISTED))
                    : catalog.status(fullName, login, token);
            return Listing.of(repo, status);
        } catch (Exception unreadable) {
            return Listing.NONE;
        }
    }

    /**
     * Delists a bot: the pull request removing its catalog entry. The repository and its releases are left
     * intact, and whoever started from it stays linked. Blocking; run off the FX thread.
     */
    public ListingStatus unpublish(String owner, String repoName) throws IOException {
        String login = signedIn();
        return catalog.remove(fullName(owner, repoName), login, auth.token());
    }

    /**
     * Marks the user's published fork as no longer following its original, or following it again: the pull request
     * setting the entry's {@code detached}, so Browse Bots and New Project stop, or start again, saying "based on
     * …". GitHub still calls the repository a fork. {@link ListingStatus.State#NOT_LISTED} when the catalog does not
     * list it, which leaves nothing to mark. Blocking; run off the FX thread.
     */
    public ListingStatus setDetached(String owner, String repoName, boolean detached) throws IOException {
        String login = signedIn();
        Optional<CatalogListing.BotEntry> entry = catalog.pending(fullName(owner, repoName), login, auth.token());
        if (entry.isEmpty()) return ListingStatus.of(ListingStatus.State.NOT_LISTED);
        return catalog.put(new CatalogListing.BotEntry(entry.get().repo(), entry.get().tag(), detached), login,
                auth.token());
    }

    private String signedIn() throws IOException {
        if (auth == null || !auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        String login = auth.login(client).join();
        if (login == null || login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        return login;
    }

    /** {@code owner/repoName} as GitHub spells it, as typed when the repository cannot be read. */
    private String fullName(String owner, String repoName) {
        JsonNode repo = client.get(repoApi(owner, repoName), auth.token()).join();
        return repo == null ? owner + "/" + repoName : repo.path("full_name").asText(owner + "/" + repoName);
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
     * The topics a repository should carry: {@code current} with the bot's {@code tags} added as topics. A topic
     * is never removed, so what an author set on github.com stays; at most {@link #MAX_TOPICS}, and no tag spelt
     * like Studio's own ({@code botmaker-…}).
     */
    static List<String> topics(List<String> current, List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : current) {
            if (!t.isBlank() && out.size() < MAX_TOPICS) out.add(t);
        }
        for (String tag : tags) {
            String topic = asTopic(tag);
            if (!topic.isEmpty() && !topic.startsWith("botmaker-") && out.size() < MAX_TOPICS) out.add(topic);
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
