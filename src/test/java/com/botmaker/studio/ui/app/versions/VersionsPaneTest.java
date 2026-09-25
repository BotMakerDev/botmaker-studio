package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertEquals(SyncModel.Ownership.LOCAL_ONLY, pane.model().ownership(), "signed out");
        assertEquals("1 unsaved change", pane.model().thisComputer());

        interact(() -> pane.saveAs("Faster mining"));

        rows = await(r -> !r.isEmpty() && r.getFirst() instanceof Timeline.Version);
        Timeline.Version saved = assertInstanceOf(Timeline.Version.class, rows.getFirst());
        assertEquals("Faster mining", saved.commit().title());
        assertEquals(VersionOrigin.SAVE, saved.commit().origin());
        assertEquals("class MyBot { int edited; }", Files.readString(file));
    }

    /** A version's change reads as blocks: the one statement it changed carries {@code :diff-changed}. */
    @Test
    void aVersionShowsTheBlockItChanged() throws Exception {
        String v1 = "class MyBot {\n    void mine() {\n        int a = 1;\n        int b = 2;\n    }\n}\n";
        String v2 = v1.replace("int b = 2;", "int b = 3;");
        interact(() -> state.getFile(file).orElseThrow().setContent(v1));
        interact(() -> pane.saveAs("one"));
        await(r -> !r.isEmpty() && r.getFirst() instanceof Timeline.Version ver && "one".equals(ver.commit().title()));
        interact(() -> state.getFile(file).orElseThrow().setContent(v2));
        interact(() -> pane.saveAs("two"));
        await(r -> !r.isEmpty() && r.getFirst() instanceof Timeline.Version ver && "two".equals(ver.commit().title()));

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !marked(pane.cardsNode(), "diff-changed").isEmpty());
        List<Node> changed = marked(pane.cardsNode(), "diff-changed");
        assertEquals(2, changed.size(), "one block on each side");
    }

    private static List<Node> marked(Node root, String pseudoClass) {
        List<Node> out = new ArrayList<>();
        if (root == null) return out;
        if (root.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals(pseudoClass))) out.add(root);
        if (root instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) out.addAll(marked(child, pseudoClass));
        }
        return out;
    }
}
