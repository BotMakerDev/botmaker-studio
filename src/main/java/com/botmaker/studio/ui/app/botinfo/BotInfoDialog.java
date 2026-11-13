package com.botmaker.studio.ui.app.botinfo;

import com.botmaker.studio.ui.app.StudioWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * <i>Project ▸ Bot Info…</i> (2026-11-13): the bot's name, description and icon, alone. It was the top of the
 * Project Settings dialog, which went with the rest of what it held (spec {@code 2026-11-13-studio-window-cleanup}).
 */
public final class BotInfoDialog {

    private final Window owner;
    private final Path projectDir;

    public BotInfoDialog(Window owner, Path projectDir) {
        this.owner = owner;
        this.projectDir = projectDir;
    }

    public void show() {
        StudioWindow window = StudioWindow.modal("bot-info", "Bot Info", owner).size(520, 380).minSize(420, 300);
        Stage stage = window.stage();
        BotInfoSection info = new BotInfoSection(projectDir, stage);

        Label status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("dialog-error-text");
        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            BotInfoSection.Pending pending = info.pending();
            status.setText("");
            setBusy(true, progress, save, cancel);
            CompletableFuture.runAsync(() -> {
                try {
                    pending.save();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }).whenComplete((ok, err) -> Platform.runLater(() -> {
                setBusy(false, progress, save, cancel);
                if (err == null) stage.close();
                else status.setText(rootMessage(err));
            }));
        });
        // A write under way finishes before the window may close, so its error is never lost on a closed stage.
        stage.setOnCloseRequest(e -> {
            if (save.isDisabled()) e.consume();
        });
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, status, spacer, cancel, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 0, 0, 0));

        ScrollPane scroll = new ScrollPane(info.node());
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane(scroll, null, null, bar, null);
        root.setPadding(new Insets(16));
        window.show(root);
    }

    private static void setBusy(boolean busy, ProgressIndicator progress, Button save, Button cancel) {
        progress.setVisible(busy);
        save.setDisable(busy);
        cancel.setDisable(busy);
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
