package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The Versions tab reads what the editor holds, not only the disk: an edit never run shows as unsaved, and
 * Save version keeps it as a named milestone.
 */
class VersionsPaneTest extends FxHeadlessTest {

    private Path file;
    private ProjectState state;
    private VersionsPane pane;

    @Override
    public void start(Stage stage) {
        try {
            Path projects = Files.createTempDirectory("versions-pane");
            ProjectConfig config = ProjectConfig.forProject("MyBot", projects);
            file = config.projectPath().resolve("src/MyBot.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class MyBot {}");
            new ProjectVcs(config.projectPath()).init();

            state = new ProjectState();
            state.addFile(new ProjectFile(file, "class MyBot {}"));
            state.setActiveFile(file);
            StudioContext ctx = new StudioContext(config, state, new EventBus(false), null, null, null, null,
                    null, null, null, null);
            pane = new VersionsPane(stage, ctx, null, null, null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        stage.setScene(new Scene((Parent) pane.node(), 900, 300));
        stage.show();
    }

    private List<Timeline.Row> await(Predicate<List<Timeline.Row>> until) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> until.test(pane.rows()));
        return pane.rows();
    }

    @Test
    void anEditNeverRunIsUnsavedAndSaveVersionKeepsIt() throws Exception {
        interact(() -> state.getFile(file).orElseThrow().setContent("class MyBot { int edited; }"));
        interact(pane::refresh);

        List<Timeline.Row> rows = await(r -> !r.isEmpty() && r.getFirst() instanceof Timeline.Unsaved);
        assertEquals(new Timeline.Unsaved(1), rows.getFirst());

        interact(() -> pane.saveAs("Faster mining"));

        rows = await(r -> !r.isEmpty() && r.getFirst() instanceof Timeline.Version);
        Timeline.Version saved = assertInstanceOf(Timeline.Version.class, rows.getFirst());
        assertEquals("Faster mining", saved.commit().title());
        assertEquals(VersionOrigin.SAVE, saved.commit().origin());
        assertEquals("class MyBot { int edited; }", Files.readString(file));
    }
}
