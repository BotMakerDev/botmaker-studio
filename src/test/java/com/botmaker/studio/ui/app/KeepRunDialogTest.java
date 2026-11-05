package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Closing Studio with a run going asks to stop it or keep it; Stop is the default (plan phase 11). */
class KeepRunDialogTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        stage.show();
    }

    @Test
    void stopIsTheDefaultAndKeepIsOffered() throws Exception {
        CompletableFuture<Alert> built = new CompletableFuture<>();
        Platform.runLater(() -> built.complete(KeepRunDialog.build()));
        Alert alert = built.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(KeepRunDialog.STOP, KeepRunDialog.KEEP, ButtonType.CANCEL), alert.getButtonTypes());
        assertEquals("Stop the run or keep it running in the background?", alert.getHeaderText());
        assertTrue(((Button) alert.getDialogPane().lookupButton(KeepRunDialog.STOP)).isDefaultButton());
        assertFalse(((Button) alert.getDialogPane().lookupButton(KeepRunDialog.KEEP)).isDefaultButton());
    }

    @Test
    void eachAnswerIsItsChoice() {
        assertEquals(KeepRunDialog.Choice.STOP, KeepRunDialog.choice(Optional.of(KeepRunDialog.STOP)));
        assertEquals(KeepRunDialog.Choice.KEEP, KeepRunDialog.choice(Optional.of(KeepRunDialog.KEEP)));
        assertEquals(KeepRunDialog.Choice.CANCEL, KeepRunDialog.choice(Optional.of(ButtonType.CANCEL)));
        assertEquals(KeepRunDialog.Choice.CANCEL, KeepRunDialog.choice(Optional.empty()));
    }
}
