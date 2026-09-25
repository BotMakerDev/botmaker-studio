package com.botmaker.studio.ui.app.versions;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.ui.app.GitHubAccountBar;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What is left of the VCS panel's sharing buttons: the provenance line and the sign-in popup the strip's
 * <i>Sign in to share…</i> opens ({@code docs/refactor/39-versions.md} §2). <i>Push</i> went with 4d (<i>Save
 * to my copy</i>); <i>Propose</i> and <i>Get latest from original</i> went with 4e (<i>Suggest to author…</i>
 * and <i>Get vX.Y</i>, a real merge).
 */
final class ShareActions {

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final Consumer<String> status;
    private final Optional<BotSource> origin;

    ShareActions(Window owner, Path projectDir, GitHubAuth auth, GitHubClient client, Consumer<String> status) {
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.status = status;
        this.origin = BotSource.read(projectDir);
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
            status.accept(auth.isAuthenticated() ? "Signed in." : "Not signed in.");
            after.run();
        });
        VBox box = new VBox(bar);
        box.setPadding(new Insets(14));
        popup.setScene(ThemedWindows.scene(box));
        popup.show();
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
