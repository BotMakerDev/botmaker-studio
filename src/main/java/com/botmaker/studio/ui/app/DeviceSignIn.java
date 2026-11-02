package com.botmaker.studio.ui.app;

import com.botmaker.shared.oauth.DeviceCode;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The device-flow handshake an account bar runs — request a code, open the browser beside a code alert, poll —
 * one implementation for {@link GitHubAccountBar} and {@link GoogleAccountBar}, which differ only in the
 * provider and in which buttons they re-enable.
 */
final class DeviceSignIn {

    private DeviceSignIn() {}

    /**
     * Runs the handshake, reporting on {@code status}. {@code finished} runs on the FX thread once it ends either
     * way (re-enable the buttons, refresh); {@code signedIn} after it, only on success.
     */
    static void run(Window owner, String provider, Label status, Supplier<CompletableFuture<DeviceCode>> request,
                    Function<DeviceCode, CompletableFuture<String>> poll, Runnable finished, Runnable signedIn) {
        status.setText("Requesting a sign-in code…");
        request.get().whenComplete((code, err) -> Platform.runLater(() -> {
            if (err != null || code == null) {
                finished.run();
                status.setText("Sign-in failed: " + rootMessage(err));
                return;
            }
            Alert dialog = showCode(owner, provider, status, code);
            poll.apply(code).whenComplete((token, perr) -> Platform.runLater(() -> {
                // The device-code dialog is done either way — success or failure — so close it instead of
                // leaving a stale "waiting for authorization…" window open behind the result.
                dialog.setResult(ButtonType.OK);
                dialog.close();
                finished.run();
                if (perr != null) {
                    status.setText("Sign-in failed: " + rootMessage(perr));
                } else {
                    signedIn.run();
                }
            }));
        }));
    }

    /** Shows or hides a button, taking it out of the layout while hidden. */
    static void show(Button b, boolean visible) {
        b.setVisible(visible);
        b.setManaged(visible);
    }

    private static Alert showCode(Window owner, String provider, Label status, DeviceCode code) {
        status.setText("Enter code " + code.userCode() + " at " + code.verificationUri()
                + " — waiting for authorization…");
        BrowserLauncher.open(code.verificationUri());

        TextArea codeArea = new TextArea(code.userCode());
        codeArea.setEditable(false);
        codeArea.setPrefRowCount(1);
        codeArea.setMaxWidth(160);

        Alert a = ThemedWindows.alert(Alert.AlertType.INFORMATION);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Sign in with " + provider);
        a.setHeaderText("Authorize BotMaker Studio");
        Hyperlink link = new Hyperlink(code.verificationUri());
        link.setOnAction(e -> BrowserLauncher.open(code.verificationUri()));
        VBox content = new VBox(8,
                new Label("1. A browser is opening " + code.verificationUri() + " (or click below)."),
                link,
                new Label("2. Enter this one-time code:"),
                codeArea,
                new Label("This closes automatically once you authorize."));
        a.getDialogPane().setContent(content);
        a.show();
        return a;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        // A dropped/absent network surfaces as one of these — say so plainly instead of leaking a raw
        // "UnknownHostException: github.com" at the user.
        if (t instanceof UnknownHostException
                || t instanceof ConnectException
                || t instanceof UnresolvedAddressException) {
            return "No internet connection — check your network and try again.";
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
