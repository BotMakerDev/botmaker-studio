package com.botmaker.studio.ui.app.versions;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.ui.app.GitHubAccountBar;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The sharing buttons the VCS panel had, moved unchanged into the Versions tab until they are replaced
 * ({@code docs/refactor/39-versions.md} §7–8): <i>Push</i> (a private backup repo, until 4d's <i>Save to my
 * copy</i>), <i>Publish…</i> (the window, until 4f's side sheet), and for an installed bot <i>Propose</i> and
 * <i>Get latest from original</i> (the snapshot tree-push and the fork sync, until 4e's real merge).
 *
 * <p><b>Push is backup, not publishing.</b> The first Push offers to make a <em>private</em> repo and set it
 * as {@code origin}; later pushes just go. No release, no gallery entry, no provenance file.
 */
final class ShareActions {

    /** What the tab lends the buttons: where to report, how to show work in progress, what to refresh. */
    record Host(Consumer<String> status, Consumer<Boolean> busy, Runnable refresh) {
    }

    private final Window owner;
    private final String projectName;
    private final Path projectDir;
    private final BotPublisher publisher;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final Runnable openPublish;
    private final Supplier<ProjectVcs> vcs;
    private final Supplier<String> proposalTitle;
    private final Host host;
    private final Optional<BotSource> origin;

    ShareActions(Window owner, String projectName, Path projectDir, BotPublisher publisher, GitHubAuth auth,
                 GitHubClient client, Runnable openPublish, Supplier<ProjectVcs> vcs,
                 Supplier<String> proposalTitle, Host host) {
        this.owner = owner;
        this.projectName = projectName;
        this.projectDir = projectDir;
        this.publisher = publisher;
        this.auth = auth;
        this.client = client;
        this.openPublish = openPublish;
        this.vcs = vcs;
        this.proposalTitle = proposalTitle;
        this.host = host;
        this.origin = BotSource.read(projectDir);
    }

    /** Push, Publish…, and for an installed bot Propose… and Get latest. */
    List<Button> buttons() {
        List<Button> out = new ArrayList<>();
        Button push = new Button("Push");
        push.setTooltip(new Tooltip("Back this project's history up to a private GitHub repo. Not the same as "
                + "Publish — nothing is released or listed."));
        push.setOnAction(e -> doPush());
        out.add(push);

        Button publish = new Button("Publish…");
        publish.setOnAction(e -> { if (openPublish != null) openPublish.run(); });
        out.add(publish);

        if (origin.isPresent()) {
            Button propose = new Button("Propose…");
            propose.setTooltip(new Tooltip("Open (or update) a pull request to " + origin.get().slug() + "."));
            propose.setOnAction(e -> doPropose());
            Button sync = new Button("Get latest from original");
            sync.setTooltip(new Tooltip("Sync your fork of " + origin.get().slug()
                    + " with the upstream default branch."));
            sync.setOnAction(e -> doSync());
            out.add(propose);
            out.add(sync);
        }
        return out;
    }

    /** "Based on owner/repo @ tag", or empty for a project of the user's own. */
    Optional<String> provenance() {
        return origin.map(s -> "Based on " + s.slug() + " @ " + s.tag());
    }

    private void doPush() {
        if (!auth.isAuthenticated()) {
            host.status().accept("Sign in to GitHub to push — opening the sign-in…");
            showGitHubSignIn();
            return;
        }
        String existingRemote = new ProjectVcs(projectDir).remoteUrl();
        String repoName = backupRepoName();
        if (existingRemote == null && !confirmRepoCreation(repoName)) return;

        run(() -> {
            ProjectVcs repo = vcs.get();
            repo.ensureInitialized();
            String token = auth.token();
            String remote = repo.remoteUrl();
            String webUrl = null;
            if (remote == null) {
                String login = auth.login(client).join();
                if (login == null || login.isBlank()) throw new IOException("Could not read your GitHub account.");
                JsonNode created = createBackupRepo(login, repoName, token);
                webUrl = created.path("html_url").asText("https://github.com/" + login + "/" + repoName);
                repo.setRemote(created.path("clone_url").asText(webUrl + ".git"));
            }
            String branch = repo.push(token);
            return webUrl == null
                    ? "Pushed " + branch + " to " + shortRemote(repo.remoteUrl()) + "."
                    : "Pushed to your new private repo: " + webUrl;
        });
    }

    /** The project name as a GitHub repo name: anything outside {@code [A-Za-z0-9._-]} becomes a dash. */
    private String backupRepoName() {
        String cleaned = projectName == null ? "" : projectName.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isBlank() ? "botmaker-project" : cleaned;
    }

    /** Creates the private backup repo, translating a scope-related refusal into an actionable message. */
    private JsonNode createBackupRepo(String login, String repoName, String token) throws IOException {
        try {
            return client.ensureRepo(login, repoName,
                    "BotMaker project backup — pushed from BotMaker Studio.", true, false, token);
        } catch (Exception ex) {
            String message = rootMessage(ex);
            if (message.contains("HTTP 403") || message.contains("HTTP 404")) {
                throw new IOException("GitHub refused to create a private repo. Your sign-in probably "
                        + "predates private-repo support — sign out and back in from the GitHub button, then "
                        + "try again.");
            }
            throw new IOException("Could not create the backup repo: " + message);
        }
    }

    private boolean confirmRepoCreation(String repoName) {
        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                "This project has no GitHub remote yet.\n\nCreate a private repository “" + repoName
                        + "” on your account and push the project's history to it?\n\n"
                        + "Private means only you can see it. This is a backup, not a publish — no release is "
                        + "created and the bot is not listed anywhere.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText("Create a private backup repo?");
        return confirm.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Opens the shared GitHub device-flow bar in a popup, so Push can recover from "not signed in" in place. */
    private void showGitHubSignIn() {
        javafx.stage.Stage popup = new javafx.stage.Stage();
        if (owner != null) popup.initOwner(owner);
        popup.setTitle("GitHub account");
        GitHubAccountBar bar = new GitHubAccountBar(popup, auth, client, () -> host.status().accept(
                auth.isAuthenticated() ? "Signed in — press Push again." : "Not signed in."));
        VBox box = new VBox(bar);
        box.setPadding(new Insets(14));
        popup.setScene(ThemedWindows.scene(box));
        popup.show();
    }

    /** {@code owner/repo} out of a clone URL, for a status line that isn't a wall of URL. */
    private static String shortRemote(String url) {
        if (url == null) return "origin";
        String trimmed = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        int slash = trimmed.lastIndexOf('/', trimmed.lastIndexOf('/') - 1);
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private void doPropose() {
        if (origin.isEmpty()) return;
        BotSource src = origin.get();
        if (!auth.isAuthenticated()) {
            host.status().accept("Sign in to GitHub first (top-right) to propose changes.");
            return;
        }
        String given = proposalTitle.get();
        String title = given == null || given.isBlank() ? "Changes from BotMaker Studio" : given.trim();
        run(() -> {
            BotPublisher.PatchResult result =
                    publisher.submitPatch(projectDir, src, title, "Proposed from BotMaker Studio.");
            if (result != null && !result.pullRequestUrl().isBlank()) {
                Platform.runLater(() -> BrowserLauncher.open(result.pullRequestUrl()));
                return "Pull request ready: " + result.pullRequestUrl();
            }
            return "Changes proposed upstream.";
        });
    }

    private void doSync() {
        if (origin.isEmpty()) return;
        if (!auth.isAuthenticated()) {
            host.status().accept("Sign in to GitHub first (top-right) to sync from the original.");
            return;
        }
        host.busy().accept(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.syncFork(origin.get());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((msg, err) -> Platform.runLater(() -> {
                    host.busy().accept(false);
                    if (err == null) {
                        host.status().accept(msg);
                        return;
                    }
                    String m = rootMessage(err);
                    host.status().accept(m);
                    if (m.toLowerCase().contains("open it on github")) {
                        BrowserLauncher.open("https://github.com/" + origin.get().slug());
                    }
                }));
    }

    @FunctionalInterface
    private interface Call {
        String call() throws Exception;
    }

    private void run(Call action) {
        host.busy().accept(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return action.call();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((result, err) -> Platform.runLater(() -> {
                    host.busy().accept(false);
                    host.status().accept(err != null ? "Failed: " + rootMessage(err) : result);
                    host.refresh().run();
                }));
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
