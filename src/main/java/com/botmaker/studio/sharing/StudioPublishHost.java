package com.botmaker.studio.sharing;

import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.PublishHost;
import com.botmaker.cli.publish.Refusal;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

/**
 * Studio's {@link PublishHost} (2026-10-24): the project's JGit history, pushed to its {@code mine} with Studio's own
 * sign-in, GitHub's API for the release, and {@link CatalogListing} for the catalog's pull request. The cli's publish
 * library decides what runs and in which order; this carries each step out the way Studio always has, and tells
 * {@link BotPublisher.Run} which of the sheet's rows it is on.
 *
 * <p>The repository itself is made, made public and set as {@code mine} by the run's first step, before the library
 * runs ({@link BotPublisher.Run}), so {@link #createRepository} is never reached.
 */
final class StudioPublishHost implements PublishHost {

    private final GitHubClient client;
    private final String token;
    private final Account account;
    private final ProjectVcs vcs;
    private final PublishRequest request;
    /** Whether this publishes a fork, whose line is {@code studio/<branch>} beside the author's {@code main}. */
    private final boolean fork;
    private final String defaultBranch;
    private final Consumer<PublishPlan.Step> begin;
    private final CatalogListing catalog;
    private final String repo;
    private final String repoApi;
    /** Whether this run saw the release download without a token. */
    private boolean downloaded;
    /** What the pull request step left: the catalog's word on it. */
    private ListingStatus listing = ListingStatus.of(ListingStatus.State.NOT_LISTED);

    /**
     * @param repo          {@code owner/name} the publish goes to, as GitHub spells it
     * @param defaultBranch the repository's default branch, which a bot that is not a fork pushes to
     * @param begin         told each row of the sheet as the step it stands for starts
     */
    StudioPublishHost(GitHubClient client, String token, Account account, ProjectVcs vcs, PublishRequest request,
                      String repo, boolean fork, String defaultBranch, Consumer<PublishPlan.Step> begin) {
        this.client = client;
        this.token = token;
        this.account = account;
        this.vcs = vcs;
        this.request = request;
        this.fork = fork;
        this.defaultBranch = defaultBranch;
        this.begin = begin;
        this.catalog = new CatalogListing(client);
        this.repo = repo;
        this.repoApi = GitHubConfig.API_BASE + "/repos/" + repo;
    }

    ListingStatus listing() {
        return listing;
    }

    @Override
    public Optional<Account> account() {
        return Optional.of(account);
    }

    @Override
    public boolean isRepository() {
        return vcs.isRepo();
    }

    @Override
    public Optional<String> remoteUrl() {
        return Optional.ofNullable(vcs.remoteUrl(Remote.MINE));
    }

    @Override
    public Optional<String> head() {
        return vcs.headCommit();
    }

    /** A fork leaves out the author's releases: {@code HEAD} at the author's tag is no release of the fork's. */
    @Override
    public List<String> tagsAtHead() {
        return vcs.tagsAtHead().stream().filter(t -> !fork || vcs.isOwnRelease(t)).toList();
    }

    @Override
    public Optional<String> nearestTag() {
        return vcs.nearestTag();
    }

    @Override
    public List<String> tagsMerged() {
        return vcs.tagsMerged();
    }

    @Override
    public Optional<String> tagCommit(String tag) {
        return vcs.tagCommit(tag);
    }

    /**
     * Nothing changed that the saved version lacks. A file git does not follow is not counted: a push sends commits,
     * so it is not published either way, and the sheet saves the project as the version first.
     */
    @Override
    public boolean clean() {
        try {
            ProjectVcs.FileStatus status = vcs.status();
            return status.added().isEmpty() && status.modified().isEmpty() && status.removed().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean repositoryExists(String repo) {
        return client.get(GitHubConfig.API_BASE + "/repos/" + repo, token).join() != null;
    }

    @Override
    public boolean releaseExists(String repo, String tag) {
        return client.get(GitHubConfig.API_BASE + "/repos/" + repo + "/releases/tags/" + tag, token).join() != null;
    }

    @Override
    public void firstCommit() throws Refusal {
        throw new Refusal("this project has no saved version, and a publish publishes one. Save a version first.");
    }

    @Override
    public void createRepository(String repo) throws Refusal {
        throw new Refusal("this project has no copy on GitHub yet (its remote " + Remote.MINE.id() + "); the "
                + "repository step makes it. Retry.");
    }

    /**
     * The versions pushed to {@code mine}, never forced. A fork's line is {@code studio/<branch>}, as <i>Save to my
     * copy</i> pushes it (2026-10-23); a repository Studio once published by snapshot has a history of its own and is
     * joined first ({@link ProjectVcs#joinUnrelated}).
     */
    @Override
    public void pushHead() throws Refusal {
        begin.accept(PublishPlan.Step.PUSH);
        try {
            vcs.push(Remote.MINE, target(), token);
        } catch (IOException e) {
            throw new Refusal(e.getMessage());
        }
    }

    @Override
    public void tag(String tag) throws Refusal {
        begin.accept(PublishPlan.Step.PUSH);
        try {
            if (fork && vcs.hasTag(tag) && !vcs.isOwnRelease(tag)) {
                // The author's release by that number is here already: marking it the fork's would hide it.
                throw new Refusal("the original already has a release " + tag + ". Pick a higher version for your"
                        + " copy.");
            }
            vcs.tagHead(tag, "Published " + request.botName() + " " + tag);
            if (fork) vcs.markOwnRelease(tag);
            vcs.push(Remote.MINE, target(), token);
        } catch (IOException e) {
            throw new Refusal(e.getMessage());
        }
    }

    /** Where the versions go on {@code mine}: a fork's line beside its author's, or the default branch. */
    private String target() throws IOException {
        // A fork that stopped following keeps its line where it pushed it as one.
        boolean beside = fork || vcs.hasRemoteBranch(Remote.MINE, "studio/" + vcs.branch());
        if (beside) return "studio/" + vcs.branch();
        vcs.joinUnrelated(Remote.MINE, defaultBranch, "Joined the versions already on github.com/" + repo, token);
        return defaultBranch;
    }

    /**
     * The release, then a check that it downloads <em>without</em> a token, which is what somebody installing it has.
     * GitHub can take a moment to serve a new tag's archive, so it is asked a few times first.
     */
    @Override
    public void release(String repo, String tag) throws Refusal {
        begin.accept(PublishPlan.Step.RELEASE);
        try {
            client.post(repoApi + "/releases", mapOf("tag_name", tag, "name", tag,
                    "target_commitish", vcs.tagCommit(tag).orElse(defaultBranch),
                    "body", "Published from BotMaker Studio"), token).join();
        } catch (Exception e) {
            throw new Refusal("could not create the release " + tag + " on " + repo + ": " + rootMessage(e));
        }
        downloads(tag);
    }

    /**
     * The release downloads without a token, then the listing row begins: the pin and the catalog's rules the library
     * runs next are the listing's, so a refusal there is never the download check's.
     */
    private void downloads(String tag) throws Refusal {
        begin.accept(PublishPlan.Step.ARCHIVE);
        int slash = repo.indexOf('/');
        String url = GitHubConfig.archiveUrl(repo.substring(0, slash), repo.substring(slash + 1), tag);
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                client.getBytes(url, null).join();
                downloaded = true;
                begin.accept(PublishPlan.Step.LISTING);
                return;
            } catch (Exception e) {
                last = e;
                sleep();
            }
        }
        throw new Refusal("the release " + tag + " does not download without signing in (" + rootMessage(last)
                + "), so nobody else could install it.");
    }

    /**
     * The repository's description and its tags as topics (the catalog shows a bot's topics as its tags; the ones
     * already there are kept), then the pull request.
     */
    @Override
    public Optional<String> pullRequest(CatalogPr.Change change, Account account, Listener listener) throws Refusal {
        // A release made before (a retry after the download check failed) is checked here: the library skips the
        // release step for a version that is out, and nobody could install one that does not download.
        if (!downloaded) downloads(request.version());
        begin.accept(PublishPlan.Step.LISTING);
        try {
            if (!request.description().isBlank()) {
                client.patch(repoApi, mapOf("description", request.description()), token).join();
            }
            List<String> tagged = BotPublisher.topics(currentTopics(), request.tags());
            client.put(repoApi + "/topics", mapOf("names", tagged), token).join();
        } catch (Exception e) {
            throw new Refusal("couldn't set the description and tags of the repository: " + rootMessage(e));
        }
        try {
            listing = catalog.open(change, account.login(), token);
        } catch (IOException e) {
            throw new Refusal(e.getMessage());
        }
        return listing.url().isBlank() ? Optional.empty() : Optional.of(listing.url());
    }

    /**
     * The repository's topics now. Fails when they cannot be read: the PUT that follows replaces them all, so an
     * unread list would wipe every topic the author set.
     */
    private List<String> currentTopics() {
        JsonNode body = client.getOrFail(repoApi + "/topics", token).join();
        return StreamSupport.stream(body.path("names").spliterator(), false)
                .map(n -> n.asText("")).toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
