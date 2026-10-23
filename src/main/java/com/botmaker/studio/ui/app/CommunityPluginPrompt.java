package com.botmaker.studio.ui.app;

import com.botmaker.studio.plugin.PluginGate;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.util.concurrent.CompletableFuture;

/**
 * The question {@link PluginGate} puts before a community plugin first loads on this computer (2026-10-23).
 *
 * <p>A project binds its plugins off the FX thread, so the question is put on the FX thread and the bind waits
 * for the answer. No answer — no FX toolkit, a window that could not open — is a no.
 */
public final class CommunityPluginPrompt {

    private CommunityPluginPrompt() {}

    /** Asks, from any thread, and waits for the answer. */
    public static boolean agrees(PluginGate.Asked asked) {
        if (Platform.isFxApplicationThread()) return show(asked);
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    answer.complete(show(asked));
                } catch (RuntimeException e) {
                    answer.complete(false);
                }
            });
        } catch (IllegalStateException noToolkit) {
            return false;
        }
        return answer.join();
    }

    private static boolean show(PluginGate.Asked asked) {
        ButtonType run = new ButtonType("Run it", ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("Don't load it", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING, asked.question(), run, skip);
        alert.setHeaderText("Run this plugin?");
        return alert.showAndWait().filter(run::equals).isPresent();
    }
}
