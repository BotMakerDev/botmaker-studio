package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

import static com.botmaker.studio.ui.app.DeviceSignIn.show;

/**
 * Reusable "Sign in with GitHub" control (OAuth device flow via {@link GitHubAuth}). Shows the signed-in
 * login with <b>Sign out</b> / <b>Switch account</b> actions, or a single <b>Sign in</b> button when
 * signed out. Owns the whole device-flow handshake (request code → browser + code alert → poll, in
 * {@link DeviceSignIn}), so the publish dialog and the project-selection screen can share one implementation.
 *
 * <p>{@code onAuthChanged} fires on every state transition (signed in / out) so the host can refresh
 * dependent UI (enable Publish, re-resolve ownership badges, …).
 */
public final class GitHubAccountBar extends HBox {

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final Runnable onAuthChanged;

    private final Label statusLabel = new Label();
    private final Button signInButton = new Button("Sign in with GitHub");
    private final Button signOutButton = new Button("Sign out");
    private final Button switchButton = new Button("Switch account");

    public GitHubAccountBar(Window owner, GitHubAuth auth, GitHubClient client, Runnable onAuthChanged) {
        super(10);
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.onAuthChanged = onAuthChanged;

        setAlignment(Pos.CENTER_LEFT);
        statusLabel.setStyle("-fx-text-fill: #444;");
        signInButton.setOnAction(e -> startDeviceFlow());
        signOutButton.setOnAction(e -> {
            auth.signOut();
            refresh();
            notifyChanged();
        });
        switchButton.setOnAction(e -> {
            auth.signOut();
            startDeviceFlow();
        });

        getChildren().addAll(statusLabel, signInButton, signOutButton, switchButton);
        refresh();
    }

    /** True once a token is held (browse/install never needs this; publish does). */
    public boolean isAuthenticated() {
        return auth.isAuthenticated();
    }

    /** Re-reads auth state and updates which controls/labels are shown. */
    public void refresh() {
        boolean authed = auth.isAuthenticated();
        show(signInButton, !authed);
        show(signOutButton, authed);
        show(switchButton, authed);
        if (!authed) {
            statusLabel.setText("Not signed in to GitHub.");
            return;
        }
        statusLabel.setText("Signed in to GitHub.");
        auth.login(client).thenAccept(login -> Platform.runLater(() -> {
            if (auth.isAuthenticated() && !login.isBlank()) {
                statusLabel.setText("Signed in as " + login + ".");
            }
        }));
    }

    private void notifyChanged() {
        if (onAuthChanged != null) onAuthChanged.run();
    }

    private void startDeviceFlow() {
        signInButton.setDisable(true);
        switchButton.setDisable(true);
        DeviceSignIn.run(owner, "GitHub", statusLabel, auth::requestDeviceCode, auth::pollForToken, () -> {
            signInButton.setDisable(false);
            switchButton.setDisable(false);
            refresh();
        }, this::notifyChanged);
    }
}
