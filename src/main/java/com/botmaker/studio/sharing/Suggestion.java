package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <i>Suggest to author…</i> ({@code docs/refactor/39-versions.md} §7): this computer's versions, pushed to the
 * user's copy as {@code suggest/<slug of the title>}, and a pull request from that branch to the original's
 * default branch — or the one already open from it. The versions are real commits on the author's history, so
 * the author reviews exactly what changed since their release. It replaced {@code BotPublisher.submitPatch},
 * which pushed a snapshot tree with no history under it. The caller saves first. Blocking; off the FX thread.
 */
public final class Suggestion {

    private final GitHubClient client;

    public Suggestion(GitHubClient client) {
        this.client = client;
    }

    /** @return the pull request's address */
    public String suggest(ProjectVcs vcs, SyncModel model, String projectName, String title, String body,
                          String token) throws IOException {
        SyncModel.Slug original = model.originalSlug()
                .orElseThrow(() -> new IOException("This bot has no author to suggest to."));
        String login = model.facts().login();
        new MyCopy(client).ensure(vcs, model, projectName, token);
        String branch = "suggest/" + slug(title);
        vcs.push(Remote.MINE, branch, token, true);

        String api = GitHubConfig.API_BASE + "/repos/" + original;
        JsonNode existing = client.get(api + "/pulls?state=open&head=" + login + ":" + branch, token).join();
        if (existing != null && existing.isArray() && !existing.isEmpty()) {
            return existing.get(0).path("html_url").asText("");
        }
        JsonNode repo = client.get(api, token).join();
        if (repo == null) throw new IOException(original + " is not reachable on GitHub.");
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("title", title);
        pr.put("head", login + ":" + branch);
        pr.put("base", repo.path("default_branch").asText("main"));
        pr.put("body", body);
        try {
            return client.post(api + "/pulls", pr, token).join().path("html_url").asText("");
        } catch (Exception e) {
            Throwable t = e;
            while (t.getCause() != null) t = t.getCause();
            throw new IOException("GitHub would not open the suggestion: " + t.getMessage(), e);
        }
    }

    /** A title as a branch name: lower case, dashes between words, at most 40 characters; never empty. */
    public static String slug(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        return s.isEmpty() ? "change" : s;
    }
}
