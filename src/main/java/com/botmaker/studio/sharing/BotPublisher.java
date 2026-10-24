package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.Publish;
import com.botmaker.cli.publish.PublishHost;
import com.botmaker.cli.publish.Refusal;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Level;
import com.botmaker.cli.validate.CheckResult;
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
 * <p>A publish is {@link PublishPlan.Step}'s steps. The first is Studio's own: the author's GitHub repository exists,
 * is public and is the project's {@code mine}. <b>Every step after it is the cli's publish library</b>
 * ({@link Publish}, 2026-10-24), the one {@code botmaker bot publish} runs: the catalog's rules on the saved version,
 * the tag pushed, the release, the release pinned to its commit, the entry with that release appended, and the
 * catalog's pull request. Studio carries each step out through its own {@link StudioPublishHost} (JGit and its
 * sign-in, never the {@code gh} program), and the rules and their order are the library's. The catalog's check merges
 * the pull request; nobody reviews it. Unpublishing and <i>Stop following</i> are pull requests too: the entry
 * removed, or marked {@code detached}.
 *
 * <p><b>A publish is a {@link Run}, resumed rather than restarted.</b> The library skips each part already done — a
 * tag that exists at {@code HEAD}, a release that is out, an entry that already lists it — so a retry runs it again
 * whole and continues from the step that failed.
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
    /** Who asked, as a catalog pull request's body says. */
    static final String COMMAND = "BotMaker Studio";

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
     * One publish, and what its repository step learnt: the account, the repository's URL and default branch.
     *
     * <p>Confined to one thread at a time; the dialog never resumes a run that is already running.
     */
    public final class Run {

        private final PublishRequest request;
        private final ProjectVcs vcs;
        /** Whose repository this publishes: the account, or an owner it can push to. */
        private final String owner;
        private PublishPlan plan;
        /** The row the library is on, while it runs. */
        private PublishPlan.Step current;
        private Consumer<PublishPlan> onChange = plan -> { };

        private PublishHost.Account account;
        private String token;
        private String repoApi;
        private String repoUrl;
        /** {@code owner/name} as GitHub spells it, which is what the catalog's entry names. */
        private String fullName;
        private String branch;
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
         * Runs the repository step if it is not done, then the library from its first step, until it lists the
         * version or refuses, reporting every transition to {@code onChange} on the calling thread. Blocking.
         */
        public PublishPlan resume(Consumer<PublishPlan> onChange) {
            this.onChange = onChange;
            current = null;
            try {
                if (plan.state(PublishPlan.Step.REPO).status() != PublishPlan.Status.DONE) {
                    begin(PublishPlan.Step.REPO);
                    String repo = ensureRepository();
                    plan = plan.done(PublishPlan.Step.REPO, repo);
                    current = null;
                    onChange.accept(plan);
                } else {
                    // A retry: the run learns the repository again, quietly.
                    ensureRepository();
                }
            } catch (Exception e) {
                return fail(rootMessage(e));
            }
            StudioPublishHost host = new StudioPublishHost(client, token, account, vcs, request, fullName,
                    isFork(vcs, owner), branch, this::begin);
            GitHubFacts facts = GitHubFacts.withToken(token);
            CatalogPr.Reader reader = CatalogPr.Reader.of(facts);
            Publish publish = new Publish(facts, reader, new StudioChecks(reader, this::begin), Jitpack.Probe.real(),
                    Jitpack.Sleeper.real());
            // The cli's own request: a bot, the version the sheet names exactly, in the repository just made sure of.
            Publish.Outcome outcome;
            try {
                outcome = publish.run(new com.botmaker.cli.publish.PublishRequest(request.projectDir(),
                        EntryPath.Kind.BOT, Level.DEFAULT, Optional.of(request.version()), Optional.of(fullName),
                        false, COMMAND), QUIET, host);
            } catch (RuntimeException e) {
                // GitHub answering with an error the library does not expect (a rate limit, a 5xx): the row it was
                // on fails, never stays running.
                return fail(rootMessage(e));
            }
            return switch (outcome) {
                case Publish.Outcome.Opened opened -> {
                    listing = host.listing();
                    yield finish(listing.describe());
                }
                case Publish.Outcome.AlreadyListed listed -> {
                    listing = ListingStatus.of(ListingStatus.State.LISTED);
                    yield finish("The catalog lists " + listed.tag() + " already.");
                }
                case Publish.Outcome.WouldList would -> finish("");
                case Publish.Outcome.Refused refused -> fail(worded(refused.reason()));
            };
        }

        /**
         * The library's refusal in Studio's words where its advice is a command line's: a tag left on an earlier
         * save that did not publish cannot be "checked out" from the sheet.
         */
        private String worded(String reason) {
            if (reason.startsWith("tag " + request.version() + " exists at")) {
                return "Version " + request.version() + " is already on an earlier save of this project (or the "
                        + "original's), which was not published. Pick a higher version.";
            }
            return reason;
        }

        /** The library has begun {@code step}: the row before it is done. */
        private void begin(PublishPlan.Step step) {
            if (step == current) return;
            if (current != null) plan = plan.done(current, "");
            plan = plan.running(step);
            current = step;
            onChange.accept(plan);
        }

        /** Listed: the last row says what the catalog said, and a row the library skipped was done already. */
        private PublishPlan finish(String said) {
            for (PublishPlan.StepState state : plan.steps()) {
                PublishPlan.Step step = state.step();
                if (step == current || step == PublishPlan.Step.LISTING) {
                    plan = plan.done(step, step == PublishPlan.Step.LISTING ? said : "");
                } else if (state.status() != PublishPlan.Status.DONE && state.status() != PublishPlan.Status.SKIPPED) {
                    plan = plan.done(step, "done already");
                }
            }
            current = null;
            onChange.accept(plan);
            return plan;
        }

        /** Refused: on the row it was on, or the next one to run. */
        private PublishPlan fail(String why) {
            PublishPlan.Step at = current != null ? current : plan.next().orElse(PublishPlan.Step.LISTING);
            plan = plan.failed(at, why);
            current = null;
            onChange.accept(plan);
            return plan;
        }

        private void account() throws IOException {
            if (account != null) return;
            if (!auth.isAuthenticated()) {
                throw new IOException("Not signed in to GitHub.");
            }
            token = auth.token();
            JsonNode user = client.get(GitHubConfig.API_BASE + "/user", token).join();
            String name = user == null ? "" : user.path("login").asText("");
            if (name.isBlank()) {
                throw new IOException("Could not read your GitHub account.");
            }
            account = new PublishHost.Account(name, user.path("id").asLong(-1));
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
            String login = account.login();
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
    }

    /** The library's progress, which the sheet's rows already show; its refusal is the outcome. */
    private static final Listener QUIET = new Listener() {
        @Override
        public void step(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void checks(List<CheckResult> results) {}
    };

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
                    ? ListingStatus.of(catalog.listed(fullName, token) ? ListingStatus.State.LISTED
                            : ListingStatus.State.NOT_LISTED)
                    : catalog.status(fullName, login, token);
            return Listing.of(repo, status);
        } catch (Exception unreadable) {
            return Listing.NONE;
        }
    }

    /**
     * Delists a bot: the pull request removing its catalog entry, after the library's own check that the account
     * controls it ({@link CatalogPr#unpublish}). The repository and its releases are left intact, and whoever started
     * from it stays linked. Blocking; run off the FX thread.
     */
    public ListingStatus unpublish(String owner, String repoName) throws IOException {
        PublishHost.Account account = signedIn();
        String token = auth.token();
        GitHubFacts facts = GitHubFacts.withToken(token);
        String repo = fullName(owner, repoName);
        EntryPath path = botPath(repo);
        CatalogPr.Reader reader = CatalogPr.Reader.of(facts);
        if (reader.listed(path).isEmpty()) return ListingStatus.of(ListingStatus.State.NOT_LISTED);
        ListingStatus[] opened = {ListingStatus.of(ListingStatus.State.NOT_LISTED)};
        try {
            CatalogPr.unpublish(path, Optional.empty(), account, COMMAND, facts, reader, (change, who, listener) -> {
                try {
                    opened[0] = open(change, who, token);
                } catch (IOException e) {
                    throw new Refusal(e.getMessage());
                }
                return Optional.of(opened[0].url()).filter(url -> !url.isBlank());
            }, QUIET);
        } catch (Refusal e) {
            throw new IOException(e.getMessage(), e);
        }
        return opened[0];
    }

    /**
     * Marks the user's published fork as no longer following its original, or following it again: the pull request
     * setting the entry's {@code detached}, so Browse Bots and New Project stop, or start again, saying "based on
     * …". GitHub still calls the repository a fork. {@link ListingStatus.State#NOT_LISTED} when the catalog does not
     * list it, which leaves nothing to mark. Blocking; run off the FX thread.
     */
    public ListingStatus setDetached(String owner, String repoName, boolean detached) throws IOException {
        PublishHost.Account account = signedIn();
        String token = auth.token();
        Optional<BotEntry> entry = CatalogPr.Reader.of(GitHubFacts.withToken(token))
                .listed(botPath(fullName(owner, repoName)))
                .filter(BotEntry.class::isInstance).map(BotEntry.class::cast);
        if (entry.isEmpty()) return ListingStatus.of(ListingStatus.State.NOT_LISTED);
        BotEntry marked = new BotEntry(entry.get().repo(), entry.get().releases(), detached);
        return open(CatalogPr.Change.put(marked, COMMAND), account, token);
    }

    private ListingStatus open(CatalogPr.Change change, PublishHost.Account account, String token)
            throws IOException {
        return catalog.open(change, account.login(), token);
    }

    private static EntryPath botPath(String repo) throws IOException {
        return EntryPath.parse(EntryPath.bot(repo)).orElseThrow(() -> new IOException(repo + " cannot be listed: the "
                + "catalog names a bot's file after owner/name, and this one does not fit."));
    }

    private PublishHost.Account signedIn() throws IOException {
        if (auth == null || !auth.isAuthenticated()) {
            throw new IOException("Not signed in to GitHub.");
        }
        JsonNode user = client.get(GitHubConfig.API_BASE + "/user", auth.token()).join();
        String login = user == null ? "" : user.path("login").asText("");
        if (login.isBlank()) {
            throw new IOException("Could not read your GitHub account.");
        }
        return new PublishHost.Account(login, user.path("id").asLong(-1));
    }

    /** {@code owner/repoName} as GitHub spells it, as typed when the repository cannot be read. */
    private String fullName(String owner, String repoName) {
        JsonNode repo = client.get(repoApi(owner, repoName), auth.token()).join();
        return repo == null ? owner + "/" + repoName : repo.path("full_name").asText(owner + "/" + repoName);
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
    // The push, release, download check and listing steps written here went on 2026-10-24 for the cli's library
    // (StudioPublishHost carries them out), and with them CatalogListing's copy of the catalog's rules 3 and 5.

    /** True when two GitHub URLs name one repository, whatever their case or {@code .git} suffix. */
    static boolean sameRepo(String a, String b) {
        var x = SyncModel.slug(a);
        var y = SyncModel.slug(b);
        return x.isPresent() && y.isPresent() && x.get().toString().equalsIgnoreCase(y.get().toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
