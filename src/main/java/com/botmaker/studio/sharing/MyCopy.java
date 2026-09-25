package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * <i>Save to my copy</i> ({@code docs/refactor/39-versions.md} §2, §7): makes sure the bot has a {@code mine} —
 * a new private repository for the user's own bot, their fork for someone else's, their own repository when
 * they installed a bot they wrote — and pushes this computer's versions to it. The caller saves first, so a
 * push always carries a real commit. Blocking; off the FX thread.
 */
public final class MyCopy {

    private final GitHubClient client;

    public MyCopy(GitHubClient client) {
        this.client = client;
    }

    /**
     * @param projectName names the repository a new bot gets
     * @return a sentence for the status line
     */
    public String save(ProjectVcs vcs, SyncModel model, String projectName, String token) throws IOException {
        String login = model.facts().login();
        if (login == null || token == null) throw new IOException("Sign in to GitHub to save to your copy.");
        String created = null;
        if (vcs.remoteUrl(Remote.MINE) == null) created = ensureMine(vcs, model, projectName, login, token);
        String remoteBranch = model.remoteBranch(vcs.branch());
        vcs.push(Remote.MINE, remoteBranch, token);
        String where = SyncModel.slug(vcs.remoteUrl(Remote.MINE)).map(SyncModel.Slug::toString).orElse("your copy");
        return created != null ? created : "Saved to your copy (" + where + ").";
    }

    /** Points {@code mine} somewhere, creating it if need be; returns the sentence that says what was made. */
    private String ensureMine(ProjectVcs vcs, SyncModel model, String projectName, String login, String token)
            throws IOException {
        switch (model.ownership()) {
            case OTHERS -> {
                SyncModel.Slug original = model.originalSlug()
                        .orElseThrow(() -> new IOException("This bot's original is not on GitHub."));
                JsonNode fork = fork(original, login, token);
                vcs.setRemote(Remote.MINE, fork.path("clone_url").asText(
                        "https://github.com/" + login + "/" + original.repo() + ".git"));
                return "Made your copy of " + original + " and saved to it ("
                        + fork.path("full_name").asText(login + "/" + original.repo()) + ").";
            }
            case OWN_PUBLISHED -> {
                // The user installed their own bot: its original is their repository.
                SyncModel.Slug own = model.originalSlug()
                        .orElseThrow(() -> new IOException("This project has no copy on GitHub yet."));
                vcs.setRemote(Remote.MINE, "https://github.com/" + own + ".git");
                return null;
            }
            case OWN_NEW -> {
                String name = repoName(projectName);
                JsonNode repo = createPrivate(login, name, token);
                vcs.setRemote(Remote.MINE, repo.path("clone_url").asText("https://github.com/" + login + "/" + name + ".git"));
                return "Saved to your new private repository " + login + "/" + name + ".";
            }
            case LOCAL_ONLY -> throw new IOException("Sign in to GitHub to save to your copy.");
            default -> throw new IllegalStateException(model.ownership().name());
        }
    }

    /** Forks {@code original} under the signed-in account (idempotent on GitHub) and waits until it answers. */
    private JsonNode fork(SyncModel.Slug original, String login, String token) throws IOException {
        JsonNode made;
        try {
            made = client.post(GitHubConfig.API_BASE + "/repos/" + original + "/forks", Map.of(), token).join();
        } catch (Exception e) {
            throw new IOException("GitHub would not make your copy of " + original + ": " + rootMessage(e), e);
        }
        String full = made == null ? login + "/" + original.repo() : made.path("full_name").asText(login + "/" + original.repo());
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonNode n = client.get(GitHubConfig.API_BASE + "/repos/" + full, token).join();
            if (n != null && n.hasNonNull("clone_url")) return n;
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("GitHub is still making your copy of " + original + " — try again in a moment.");
    }

    private JsonNode createPrivate(String login, String name, String token) throws IOException {
        try {
            return client.ensureRepo(login, name, "A BotMaker bot — saved from BotMaker Studio.", true, false, token);
        } catch (Exception ex) {
            String message = rootMessage(ex);
            if (message.contains("HTTP 403") || message.contains("HTTP 404")) {
                throw new IOException("GitHub refused to create a private repository. Your sign-in probably "
                        + "predates private-repository support — sign out and back in, then try again.");
            }
            throw new IOException("Could not create your repository: " + message);
        }
    }

    /** The project name as a GitHub repository name: anything outside {@code [A-Za-z0-9._-]} becomes a dash. */
    static String repoName(String projectName) {
        String cleaned = projectName == null ? "" : projectName.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isBlank() ? "botmaker-project" : cleaned;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
