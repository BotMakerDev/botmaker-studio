package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.BlockDiff;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A parameter turned from a list into a map is shown as the two blocks the canvas drew, not two lines of Java
 * (reported 2026-09-26).
 */
class BlockPreviewFieldTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) { }

    @Test
    void aChangedFieldIsDrawnOnBothSides() throws Exception {
        String before = """
                package com.mybot;
                public class Parameters {
                    static java.util.List<String> names = java.util.List.of("a");
                }
                """;
        String after = before.replace("java.util.List<String> names = java.util.List.of(\"a\")",
                "java.util.Map<String, Integer> names = java.util.Map.of(\"a\", 1)");
        BlockDiff.FieldChange change = BlockDiff.of(before, after).fields().getFirst();

        ProjectState live = new ProjectState();
        live.setSourcePath(TestSupport.SOURCE_ROOT);
        live.setResolvedClasspath(TestSupport.runtimeClassPath());
        ProjectConfig config = ProjectConfig.forProject("MyBot", Path.of("build", "previews"));
        Path file = TestSupport.SOURCE_ROOT.resolve("com/mybot/Parameters.java");

        Node[] drawn = new Node[2];
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            drawn[0] = new BlockPreview(config, live, file, before).field(change.beforeStart());
            drawn[1] = new BlockPreview(config, live, file, after).field(change.afterStart());
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(drawn[0], "the list side is drawn as blocks");
        assertNotNull(drawn[1], "the map side is drawn as blocks");
    }
}
