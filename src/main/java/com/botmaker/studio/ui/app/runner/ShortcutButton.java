package com.botmaker.studio.ui.app.runner;

import com.botmaker.studio.sharing.ProgramEntries;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Run window's <i>⤴ Add shortcut</i> (2026-10-25, spec § 1b K1): a shortcut on the desktop and an entry in the
 * programs menu that open this bot's Run window, made by {@link ProgramEntries}. Once made it reads <i>✓ Shortcut</i>,
 * and a click offers to remove it. With the user's switch keeping My bots out of the menu, the menu still holds the
 * bots with a shortcut, from My bots' next read ({@code RunHome}); with it on, every bot is there anyway.
 *
 * <p>Greyed in a development run: only an installed Studio has a launcher for a shortcut to start.
 */
final class ShortcutButton {

    static final String ADD = "⤴ Add shortcut";
    static final String MADE = "✓ Shortcut";

    private ShortcutButton() {}

    static Node of(ProgramEntries.Entry entry, Window owner) {
        Button button = new Button(ADD);
        Optional<ProgramEntries> desktop = ProgramEntries.desktopOfThisComputer();
        if (desktop.isEmpty()) {
            button.setDisable(true);
            // A disabled button shows no tooltip of its own: the pane around it does.
            StackPane wrap = new StackPane(button);
            Tooltip.install(wrap, new Tooltip("Shortcuts are made by the installed BotMaker, not a development run."));
            return wrap;
        }
        ProgramEntries places = desktop.get();
        button.setTooltip(new Tooltip("A shortcut on the desktop and in the programs menu that opens this bot here"));
        Runnable show = () -> button.setText(places.has(entry) ? MADE : ADD);
        show.run();
        button.setOnAction(e -> {
            boolean made = places.has(entry);
            if (made && !removeAsked(owner)) return;
            button.setDisable(true);
            // Off the FX thread: Windows makes a shortcut through PowerShell.
            CompletableFuture.runAsync(() -> {
                try {
                    change(places, entry, !made);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex.getMessage(), ex);
                }
            }).whenComplete((done, failed) -> Platform.runLater(() -> {
                button.setDisable(false);
                show.run();
                if (failed == null) return;
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                Alert alert = ThemedWindows.alert(Alert.AlertType.ERROR, cause.getMessage(), ButtonType.OK);
                alert.initOwner(owner);
                alert.setHeaderText(made ? "Could not remove the shortcut" : "Could not add the shortcut");
                alert.showAndWait();
            }));
        });
        return button;
    }

    /**
     * Adds or removes the desktop shortcut. The menu entry is My bots' next read's to make or drop: one writer of the
     * menu, under its names ({@link ProgramEntries#sync}).
     */
    private static void change(ProgramEntries desktop, ProgramEntries.Entry entry, boolean add) throws IOException {
        if (add) desktop.add(entry);
        else desktop.remove(entry);
    }

    private static boolean removeAsked(Window owner) {
        ButtonType remove = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        Alert ask = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                "The shortcut on the desktop goes, and its programs-menu entry too unless the menu holds all your "
                        + "bots.", remove, ButtonType.CANCEL);
        ask.initOwner(owner);
        ask.setHeaderText("Remove this bot's shortcut?");
        return ask.showAndWait().filter(remove::equals).isPresent();
    }
}
