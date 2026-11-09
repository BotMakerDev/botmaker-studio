package com.botmaker.studio.ui.app;

import com.botmaker.core.project.ProjectPreferences;
import com.botmaker.core.run.capture.SessionEnvironment;
import com.botmaker.session.SessionBackend;
import com.botmaker.session.display.SessionBackends;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * A one-time notice on a Wayland session that has no private display for a bot's game: Studio's own capture works
 * on Wayland (the portal and {@code DesktopGrab}), and a bot plays in a private display, which on Wayland needs
 * {@code gamescope}. Until 2026-11-09 this was {@code ForceX11Notice}, which told the user to log in to Xorg and
 * offered to install its session packages; Plasma 6.8 has no X11 session to install, and neither Studio nor a bot
 * needs one now. A "don't show again" checkbox suppresses it on future launches.
 */
public final class WaylandNotice {

    private WaylandNotice() {}

    /** Shows the notice once if this is Wayland, gamescope is missing, and the user hasn't suppressed it. */
    public static void maybeShow(Window owner) {
        if (!SessionEnvironment.isWayland()) return;
        if (SessionBackends.isAvailable(SessionBackend.GAMESCOPE)) return;
        if (ProjectPreferences.isWaylandNoticeHidden()) return;
        show(owner);
    }

    private static void show(Window owner) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.INFORMATION);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("A private display for your bots");
        alert.setHeaderText("Install gamescope to run bots beside your desktop");

        Label body = new Label("You're running a Wayland session. Studio captures and picks windows here as it does "
                + "anywhere, but a bot plays its game in a private display, so your mouse and keyboard stay yours, "
                + "and on Wayland that display is gamescope's. Without it, a bot plays on your desktop.\n\nTo fix "
                + "it, " + SessionBackends.installHint(SessionBackend.GAMESCOPE)
                + " — on Fedora: sudo dnf install gamescope. Then restart Studio.");
        body.setWrapText(true);
        body.setMaxWidth(480);

        CheckBox dontShow = new CheckBox("Don't show this again");
        VBox content = new VBox(12, body, dontShow);
        content.setPadding(new Insets(4));
        alert.getDialogPane().setContent(content);
        alert.getButtonTypes().setAll(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        alert.setOnHidden(e -> {
            if (dontShow.isSelected()) ProjectPreferences.setWaylandNoticeHidden(true);
        });
        alert.setResizable(true);
        alert.show(); // non-blocking so startup isn't held up
    }
}
