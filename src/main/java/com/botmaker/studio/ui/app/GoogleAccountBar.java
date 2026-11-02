package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GoogleAuth;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

import static com.botmaker.studio.ui.app.DeviceSignIn.show;

/**
 * Reusable "Sign in with Google" control (OAuth device flow via {@link GoogleAuth}) — the sibling of
 * {@link GitHubAccountBar}. Shows the signed-in email with <b>Sign out</b>, or a single <b>Sign in</b> button.
 * There is no backend behind the Google identity yet, so the whole bar is hidden until
 * {@link GoogleAuth#isConfigured()} (a client id is set); this is the sign-in plumbing only.
 */
public final class GoogleAccountBar extends HBox {

    private final Window owner;
    private final GoogleAuth auth;
    private final Runnable onAuthChanged;

    private final Label statusLabel = new Label();
    private final Button signInButton = new Button("Sign in with Google");
    private final Button signOutButton = new Button("Sign out");

    public GoogleAccountBar(Window owner, GoogleAuth auth, Runnable onAuthChanged) {
        super(10);
        this.owner = owner;
        this.auth = auth;
        this.onAuthChanged = onAuthChanged;

        setAlignment(Pos.CENTER_LEFT);
        statusLabel.setStyle("-fx-text-fill: #444;");
        signInButton.setOnAction(e -> startDeviceFlow());
        signOutButton.setOnAction(e -> {
            auth.signOut();
            refresh();
            notifyChanged();
        });

        getChildren().addAll(statusLabel, signInButton, signOutButton);
        refresh();
    }

    public boolean isAuthenticated() {
        return auth.isAuthenticated();
    }

    /** Re-reads auth state and updates which controls/labels are shown. The whole bar hides until configured. */
    public void refresh() {
        boolean configured = auth.isConfigured();
        setVisible(configured);
        setManaged(configured);
        if (!configured) return;

        boolean authed = auth.isAuthenticated();
        show(signInButton, !authed);
        show(signOutButton, authed);
        if (!authed) {
            statusLabel.setText("Not signed in to Google.");
            return;
        }
        statusLabel.setText("Signed in to Google.");
        auth.email().thenAccept(email -> Platform.runLater(() -> {
            if (auth.isAuthenticated() && !email.isBlank()) {
                statusLabel.setText("Signed in as " + email + ".");
            }
        }));
    }

    private void notifyChanged() {
        if (onAuthChanged != null) onAuthChanged.run();
    }

    private void startDeviceFlow() {
        signInButton.setDisable(true);
        DeviceSignIn.run(owner, "Google", statusLabel, auth::requestDeviceCode, auth::pollForToken, () -> {
            signInButton.setDisable(false);
            refresh();
        }, this::notifyChanged);
    }
}
