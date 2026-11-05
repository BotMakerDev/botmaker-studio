package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.Optional;

/**
 * What closing Studio asks while the bot runs (plan phase 11): stop it, the default, or keep it running in the
 * background, in BotMaker's core, until it ends. A kept run is seen again on reopening its project in Studio, or through
 * the MCP.
 */
public final class KeepRunDialog {

    /** The answer; a dialog closed without one stops nothing and closes nothing. */
    public enum Choice { STOP, KEEP, CANCEL }

    static final ButtonType STOP = new ButtonType("Stop the run", ButtonBar.ButtonData.OK_DONE);
    static final ButtonType KEEP = new ButtonType("Keep it running", ButtonBar.ButtonData.OTHER);

    /** A window property marking a close Claude asked for (quit_studio): it stops the run and asks nothing. */
    private static final String UNASKED = "botmaker.close.unasked";

    private KeepRunDialog() {
    }

    /** Marks the next close of {@code window} as Claude's: nobody may be there to answer the dialog. */
    public static void closeUnasked(Window window) {
        window.getProperties().put(UNASKED, Boolean.TRUE);
    }

    /** Whether this close was marked by {@link #closeUnasked}; clears the mark. */
    public static boolean unasked(Window window) {
        return window.getProperties().remove(UNASKED) != null;
    }

    /** The dialog, Stop its default button. */
    static Alert build() {
        Alert alert = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                "The bot is running. Stop the run, or keep it running in the background? A kept run goes on "
                        + "after Studio closes; reopen the project to see it again.",
                STOP, KEEP, ButtonType.CANCEL);
        alert.setTitle("Close BotMaker Studio");
        alert.setHeaderText("Stop the run or keep it running in the background?");
        ((Button) alert.getDialogPane().lookupButton(STOP)).setDefaultButton(true);
        ((Button) alert.getDialogPane().lookupButton(KEEP)).setDefaultButton(false);
        return alert;
    }

    /** Asks over {@code owner} and waits for the answer. */
    public static Choice ask(Window owner) {
        Alert alert = build();
        if (owner != null) alert.initOwner(owner);
        return choice(alert.showAndWait());
    }

    static Choice choice(Optional<ButtonType> answered) {
        if (answered.isEmpty()) return Choice.CANCEL;
        if (answered.get() == STOP) return Choice.STOP;
        if (answered.get() == KEEP) return Choice.KEEP;
        return Choice.CANCEL;
    }
}
