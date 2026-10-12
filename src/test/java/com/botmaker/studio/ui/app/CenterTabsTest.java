package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One tab per open Java file with the canvas in the selected one, a tab per thing opened to be read, every tab
 * closable from its menu, and none at all allowed.
 */
class CenterTabsTest extends FxHeadlessTest {

    @TempDir
    Path dir;

    @Test
    void javaFilesAreTabsTheCanvasFollowsAndEveryTabCloses() throws Exception {
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");
        Path json = Files.writeString(dir.resolve("targets.json"), "{}");
        AtomicReference<Path> active = new AtomicReference<>(a);
        interact(() -> {
            Label canvas = new Label("canvas");
            CenterTabs tabs = new CenterTabs(canvas);
            tabs.setEditor(active::set, active::get);
            TabPane pane = (TabPane) ((StackPane) tabs.node()).getChildren().getFirst();

            tabs.follow();
            assertEquals(List.of("A.java"), tabs.titles(), "the first file drawn gets its tab");
            assertSame(canvas, selectedContent(pane));

            active.set(b);   // switched to from Project Files, a usage or the assistant
            tabs.follow();
            assertEquals(List.of("A.java", "B.java"), tabs.titles());
            assertSame(canvas, selectedContent(pane), "the canvas moved into B's tab");
            assertNull(pane.getTabs().getFirst().getContent());

            pane.getSelectionModel().select(pane.getTabs().getFirst());
            assertEquals(a, active.get(), "selecting a tab switches the editor to its file");

            tabs.openResource(json);
            tabs.openResource(json);
            assertEquals(List.of("A.java", "B.java", "targets.json"), tabs.titles(), "the same file is one tab");
            tabs.follow();
            assertEquals("targets.json", pane.getSelectionModel().getSelectedItem().getText(),
                    "an edit to the open file does not take the front from a viewer");

            fire(pane.getTabs().getFirst(), "Close Tabs to the Right");
            assertEquals(List.of("A.java"), tabs.titles());

            fire(pane.getTabs().getFirst(), "Close All");
            assertEquals(List.of(), tabs.titles(), "no tab at all is allowed");
            assertTrue(((StackPane) tabs.node()).getChildren().get(1).isVisible(), "and says how to open one");
            tabs.follow();
            assertEquals(List.of(), tabs.titles(), "an edit to the open file does not reopen its tab");

            tabs.showCanvas();
            assertEquals(List.of("A.java"), tabs.titles(), "asking for the file again does");
        });
    }

    @Test
    void aProjectReopensWithTheTabsItWasLeftWith() throws Exception {
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");
        AtomicReference<Path> active = new AtomicReference<>(a);
        interact(() -> {
            CenterTabs tabs = new CenterTabs(new Label("canvas"));
            tabs.setEditor(active::set, active::get);
            tabs.restore(List.of(a, b, dir.resolve("Gone.java")), b);
            assertEquals(a, active.get(), "nothing switches before the project's first file is drawn");

            tabs.follow();   // the entry point, drawn by the open
            assertEquals(List.of("A.java", "B.java"), tabs.titles(), "a file that is gone is not reopened");
            assertEquals(b, tabs.shownJavaFile());
            assertEquals(b, active.get());
        });
        Files.delete(b);
        interact(() -> {
            CenterTabs tabs = new CenterTabs(new Label("canvas"));
            tabs.setEditor(active::set, active::get);
            tabs.restore(List.of(a), a);
            tabs.follow();
            assertEquals(List.of("A.java"), tabs.titles());
        });
    }

    @Test
    void aRenamedOrDeletedFilesTabCloses() throws Exception {
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");
        AtomicReference<Path> active = new AtomicReference<>(a);
        AtomicReference<CenterTabs> tabs = new AtomicReference<>();
        interact(() -> {
            tabs.set(new CenterTabs(new Label("canvas")));
            tabs.get().setEditor(active::set, active::get);
            tabs.get().follow();
            tabs.get().openJava(b);
            assertEquals(b, active.get());
        });
        Path json = Files.writeString(dir.resolve("targets.json"), "{}");
        interact(() -> tabs.get().openResource(json));
        Files.delete(b);
        Files.delete(json);
        interact(() -> {
            tabs.get().closeMissing();
            assertEquals(List.of("A.java"), tabs.get().titles(), "a viewer's file that is gone closes too");
            assertEquals(b, active.get(), "the open file's rename or delete switches the editor, not the tabs");

            active.set(a);   // the delete switches to the entry point
            tabs.get().follow();
            assertEquals(a, tabs.get().shownJavaFile());
        });
    }

    @Test
    void restoredTabsOpenOnTheirFrontFileAndAllGoneIsNeverSaved() throws Exception {
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");
        AtomicReference<Path> active = new AtomicReference<>(a);
        interact(() -> {
            CenterTabs tabs = new CenterTabs(new Label("canvas"));
            tabs.setEditor(active::set, active::get);
            tabs.restore(List.of(a, b), b);
            assertEquals(b, tabs.restoredFront(), "the project opens on the file it was left showing");

            CenterTabs gone = new CenterTabs(new Label("canvas"));
            gone.setEditor(active::set, active::get);
            gone.restore(List.of(dir.resolve("Gone.java")), null);
            gone.follow();
            assertEquals(List.of("A.java"), gone.titles(), "saved files all gone: the first file drawn has a tab");

            CenterTabs closed = new CenterTabs(new Label("canvas"));
            closed.setEditor(active::set, active::get);
            closed.restore(List.of(), null);
            closed.follow();
            assertEquals(List.of(), closed.titles(), "every tab closed stays closed");
        });
    }

    private static Node selectedContent(TabPane pane) {
        return pane.getSelectionModel().getSelectedItem().getContent();
    }

    private static void fire(Tab tab, String item) {
        MenuItem found = tab.getContextMenu().getItems().stream()
                .filter(i -> item.equals(i.getText())).findFirst().orElseThrow();
        found.fire();
    }
}
