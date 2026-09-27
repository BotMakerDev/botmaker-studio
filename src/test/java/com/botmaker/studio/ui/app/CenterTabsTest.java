package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The canvas stays first and alone until something is opened beside it, and one thing gets one tab. */
class CenterTabsTest extends FxHeadlessTest {

    @TempDir
    Path dir;

    @Test
    void aFileOpensOnceBesideTheCanvasAndTheStripHidesWhenItCloses() throws Exception {
        Path json = Files.writeString(dir.resolve("targets.json"), "{}");
        interact(() -> {
            CenterTabs tabs = new CenterTabs(new Label("canvas"));
            TabPane pane = (TabPane) tabs.node();
            tabs.setCanvasTitle("Bot.java");
            assertTrue(pane.getStyleClass().contains("center-tabs--single"), "the canvas alone shows no strip");

            tabs.openResource(json);
            tabs.openResource(json);
            assertEquals(List.of("Bot.java", "targets.json"), tabs.titles(), "the same file is one tab");
            assertFalse(pane.getStyleClass().contains("center-tabs--single"));
            assertEquals("targets.json", pane.getSelectionModel().getSelectedItem().getText());

            tabs.showCanvas();
            assertEquals("Bot.java", pane.getSelectionModel().getSelectedItem().getText());

            Tab viewer = pane.getTabs().get(1);
            pane.getTabs().remove(viewer);
            viewer.getOnClosed().handle(null);
            assertTrue(pane.getStyleClass().contains("center-tabs--single"));
            assertFalse(pane.getTabs().getFirst().isClosable(), "the canvas is never closed");
        });
    }
}
