package com.botmaker.studio.ui.app.versions;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.ui.app.GitHubAccountBar;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The sharing actions the VCS panel had that are not replaced yet ({@code docs/refactor/39-versions.md} §7–8):
 * for an installed bot <i>Propose</i> and <i>Get latest from original</i> (the snapshot tree-push and the fork
 * sync, until 4e's <i>Suggest to author</i> and real merge), plus the sign-in popup the strip's <i>Sign in to
 * share…</i> opens. The backup <i>Push</i> went with 4d: <i>Save to my copy</i> is that repository now.
 */
final class ShareActions {

    /** What the tab lends the buttons: where to report, how to show work in progress, what to refresh. */
    record Host(Consumer<String> status, Consumer<Boolean> busy, Runnable refresh) {
    }

    private final Window owner;
    private final Path projectDir;
    private final BotPublisher publisher;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final Supplier<String> proposalTitle;
    private final Host host;
    private final Optional<BotSource> origin;

    ShareActions(Window owner, Path projectDir, BotPublisher publisher, GitHubAuth auth, GitHubClient client,
                 Supplier<String> proposalTitle, Host host) {
        this.owner = owner;
        this.projectDir = projectDir;
        this.publisher = publisher;
        this.auth = auth;
        this.client = client;
        this.proposalTitle = proposalTitle;
        this.host = host;
        this.origin = BotSource.read(projectDir);
    }

    /** For an installed bot, Propose… and Get latest; nothing otherwise. */
    List<Button> buttons() {
        List<Button> out = new ArrayList<>();
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

    /** Opens the shared GitHub device-flow bar in a popup; {@code after} runs when the sign-in changes. */
    void signIn(Runnable after) {
        if (auth == null || client == null) return;
        javafx.stage.Stage popup = new javafx.stage.Stage();
        if (owner != null) popup.initOwner(owner);
        popup.setTitle("GitHub account");
        GitHubAccountBar bar = new GitHubAccountBar(popup, auth, client, () -> {
            host.status().accept(auth.isAuthenticated() ? "Signed in." : "Not signed in.");
            after.run();
        });
        VBox box = new VBox(bar);
        box.setPadding(new Insets(14));
        popup.setScene(ThemedWindows.scene(box));
        popup.show();
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
