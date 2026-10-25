package com.botmaker.studio.ui.app.loading;

import com.botmaker.studio.services.ProgressReporter;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * A reload that only changes files (2026-10-25, run-mode spec § 1b, layout R1): the editor stays in view, dimmed and
 * out of reach, under a small box — "Switching to {@code <branch>}…", a bar and "Reloading N changed files" — until
 * the reloaded editor takes the window. FX thread, but for {@link #reporter}.
 */
public final class ReloadBox {

    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final VBox box;

    private ReloadBox(String branch, int changedFiles) {
        Label title = new Label("Switching to " + branch + "…");
        title.getStyleClass().add("np-card-name");
        Label files = new Label("Reloading " + changedFiles + (changedFiles == 1 ? " changed file" : " changed files"));
        files.getStyleClass().add("gallery-card-note");
        bar.setPrefWidth(260);
        box = new VBox(8, title, bar, files);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("reload-box");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    /** Puts the box over {@code scene}'s window as it is now. */
    public static ReloadBox over(Scene scene, String branch, int changedFiles) {
        ReloadBox reload = new ReloadBox(branch, changedFiles);
        Parent shown = scene.getRoot();
        shown.setDisable(true);
        Region dim = new Region();
        dim.getStyleClass().add("reload-dim");
        StackPane stack = new StackPane();
        scene.setRoot(stack);
        stack.getChildren().addAll(shown, dim, reload.box);
        return reload;
    }

    /** The box's bar, from the open's thread; its steps have no row here. */
    public ProgressReporter reporter() {
        return (fraction, message) -> Platform.runLater(() ->
                bar.setProgress(fraction >= 0 ? fraction : ProgressBar.INDETERMINATE_PROGRESS));
    }
}
