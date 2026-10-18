package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file switched away from keeps its root's node in the canvas's scene, hidden (2026-10-18), and a switch back
 * shows that node again where it was scrolled to: not built, not put back in a parent, so not styled again.
 */
class KeptCanvasTest extends FxHeadlessTest {

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        Scene scene = new Scene(root, 800, 400);
        ThemedWindows.addStylesheet(scene);
        ThemedWindows.applyThemeClass(root);
        stage.setScene(scene);
        stage.show();
    }

    /** Enough constants to scroll. */
    private static String source() {
        StringBuilder sb = new StringBuilder("package com.mybot;\npublic class Subject {\n");
        for (int i = 0; i < 60; i++) sb.append("    public static final int C").append(i).append(" = ").append(i).append(";\n");
        return sb.append("}\n").toString();
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            work.run();
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread should have run the work");
        for (int i = 0; i < 3; i++) {
            CountDownLatch drained = new CountDownLatch(1);
            Platform.runLater(drained::countDown);
            assertTrue(drained.await(10, TimeUnit.SECONDS), "a deferred pulse should have run");
        }
    }

    private EditorCanvas drawn(EditorFixture fixture) throws Exception {
        EditorCanvas[] made = new EditorCanvas[1];
        onFx(() -> {
            made[0] = new EditorCanvas(fixture.context(), fixture.bus(),
                    false, "Subject", () -> {}, List::of, () -> {}, List::of, () -> {}, List::of);
            root.getChildren().setAll(made[0].node());
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class,
                    made[0]::handleBlocksUpdate, false);
            root.applyCss();
            root.layout();
        });
        onFx(fixture::rerender);
        return made[0];
    }

    private static ProjectFile otherFile(EditorFixture fixture) {
        ProjectFile other = new ProjectFile(Paths.get("Other.java").toAbsolutePath(),
                "package com.mybot;\npublic class Other {\n    public void go() {\n        int c = 3;\n    }\n}\n");
        fixture.state.addFile(other);
        return other;
    }

    @Test
    void a_file_switched_back_to_shows_the_node_it_kept_in_the_scene_where_it_was_scrolled_to() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        CodeEditorService context = fixture.context();
        Path subject = fixture.state.getActiveFile().getPath();
        ProjectFile other = otherFile(fixture);
        ScrollPane scroll = (ScrollPane) canvas.node().lookup(".code-scroll-pane");

        Node subjectRoot = context.getRootBlock().orElseThrow().getUINode();
        onFx(() -> scroll.setVvalue(0.6));
        onFx(() -> context.switchToFile(other.getPath()));
        Node otherRoot = context.getRootBlock().orElseThrow().getUINode();
        onFx(() -> {
            assertNotNull(subjectRoot.getScene(), "the file left stays in the scene, so it is not styled again");
            assertFalse(subjectRoot.isVisible(), "hidden");
            assertFalse(subjectRoot.isManaged(), "and out of the layout");
            assertTrue(otherRoot.isVisible());
        });

        onFx(() -> context.switchToFile(subject));
        onFx(() -> {
            assertSame(subjectRoot, context.getRootBlock().orElseThrow().getUINode(), "the node it had");
            assertTrue(subjectRoot.isVisible() && subjectRoot.isManaged(), "shown again");
            assertFalse(otherRoot.isVisible(), "the other one hidden in turn");
            assertNotNull(otherRoot.getScene(), "and kept");
            assertEquals(0.6, scroll.getVvalue(), 0.02, "where it was scrolled to");
        });
    }

    @Test
    void a_kept_root_the_service_drops_leaves_the_scene() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        CodeEditorService context = fixture.context();
        ProjectFile other = otherFile(fixture);

        Node subjectRoot = context.getRootBlock().orElseThrow().getUINode();
        onFx(() -> context.switchToFile(other.getPath()));
        onFx(fixture::rerender);   // a rebind: every kept render is dropped
        onFx(() -> assertNull(subjectRoot.getScene(), "a rebind drops the hidden roots too"));
        assertNotNull(canvas.node().getScene());
    }
}
