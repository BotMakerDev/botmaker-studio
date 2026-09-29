package com.botmaker.studio.ui.fx;

import com.botmaker.studio.ui.app.StudioWindow;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The shell keeps one scene and swaps screens in as roots (2026-09-29).
 *
 * <p>What this cannot show headless is why: a scene set on a maximized stage under KWin came up at the
 * window's restored size, and the flag toggle that re-filled it could leave the window un-maximized — the
 * "resizes for no reason" report, reproduced with {@code GeometryTrace} in a nested KWin. What it holds is the
 * invariant that keeps that path closed: after the first screen, {@code setScene} is never called again.
 */
class ShellSceneSwapTest extends FxHeadlessTest {

    private Stage stage;
    private Scene first;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        StudioWindow.showOnShell(stage, new Scene(new VBox()));
        // TestFX hands every test class the same primary stage, which may carry an earlier class's scene: the
        // shell's scene is whichever is showing now.
        first = stage.getScene();
        stage.show();
    }

    @Test
    void theFirstScreenIsTheStagesSceneAndEveryLaterOneOnlyItsRoot() throws Exception {
        StackPane editor = new StackPane();
        Scene built = new Scene(editor);
        built.getStylesheets().add("data:text/css,.x{}");

        interact(() -> StudioWindow.showOnShell(stage, built));

        assertSame(first, stage.getScene(), "the shell's scene is never replaced");
        assertSame(editor, stage.getScene().getRoot());
        assertEquals(built.getStylesheets(), stage.getScene().getStylesheets());
        assertInstanceOf(Group.class, built.getRoot(), "the carrier gave its root up");
    }
}
