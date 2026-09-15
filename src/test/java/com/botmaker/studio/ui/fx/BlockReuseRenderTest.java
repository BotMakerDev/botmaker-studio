package com.botmaker.studio.ui.fx;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.services.CodeEditorService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one claim about reuse that neither {@code BlockReuseTest} nor an adoption test can make: that the
 * JavaFX {@link Node} handed back after a re-parse is the <b>same object</b>.
 *
 * <p>Everything else about reuse is decided on strings and AST offsets and is asserted headlessly. This is
 * the end of the chain — a block survived, so its cached {@code uiNode} survived with it, so the caret, the
 * selection and an open popup inside it survived too. A test that only asserted block identity would pass
 * against a `getUINode` that rebuilt its node every call, which is the bug this feature exists to prevent.
 *
 * <p>It drives the real {@code CodeEditorService}: a {@code CodeUpdatedEvent} is published exactly as
 * {@code CodeEditor} publishes one after an edit, so the oracle is built and the ordering trap
 * ({@code adopt} replacing the state's source) is exercised rather than assumed.
 *
 * <p>See {@code docs/refactor/30-block-reuse.md} §11.
 */
class BlockReuseRenderTest extends FxHeadlessTest {

    /** Two statements in one method, so one can change while the other does not. */
    private static String source(String first, String second) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        " + first + "\n"
                + "        " + second + "\n"
                + "    }\n"
                + "}\n";
    }

    private VBox root;

    @Override
    public void start(Stage stage) {
        root = new VBox();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
    }

    /**
     * Renders the fixture's tree into the scene and returns the service driving it.
     *
     * <p>The render has to happen through the service rather than by calling {@code getUINode} directly:
     * {@code lastRootBlock} is what {@code focusedBlockId} walks from, and only a real render sets it.
     */
    private CodeEditorService renderInto(EditorFixture fixture) throws Exception {
        CodeEditorService context = fixture.context();
        fixture.subscribeBlocksUpdated(rootBlock -> {
            root.getChildren().setAll(rootBlock.getUINode(context));
        });
        onFx(() -> fixture.rerender());
        return context;
    }

    /** The block for the statement at {@code index} of {@code run()}'s body. */
    private static CodeBlock statement(EditorFixture fixture, int index) {
        BodyBlock body = fixture.body("run");
        return body.getStatements().get(index);
    }

    /** Runs {@code work} on the FX thread and waits for the render it triggers to drain. */
    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            work.run();
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread should have run the work");
        // render() is itself deferred one more runLater by the CodeUpdatedEvent subscription.
        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS), "the deferred render should have run");
    }

    /** Publishes the edit the way {@code CodeEditor} does after a write, and waits for the render. */
    private void edit(EditorFixture fixture, String newSource) throws Exception {
        onFx(() -> fixture.bus().publish(
                new CoreApplicationEvents.CodeUpdatedEvent(newSource, fixture.state.getCurrentCode())));
    }

    /**
     * Puts the focus on a widget inside {@code block}'s own node.
     *
     * <p>Focus is what {@code focusedBlockId} prefers, so a test about the <em>highlight</em> has to move it
     * somewhere with no block above it — {@link #focusOutsideAnyBlock} — or the scene's default focus owner
     * silently decides the answer.
     */
    private static void focusInside(CodeBlock block) {
        Node node = block.getUINode();
        node.setFocusTraversable(true);
        node.requestFocus();
    }

    /** Focus on the scene root, which carries no block id — so {@code blockIdOf} walks up to null. */
    private void focusOutsideAnyBlock() {
        root.setFocusTraversable(true);
        root.requestFocus();
    }

    @Test
    void the_focused_block_keeps_its_javafx_node_across_an_edit_elsewhere() throws Exception {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        renderInto(fixture);

        CodeBlock kept = statement(fixture, 1);
        Node keptNode = kept.getUINode();
        onFx(() -> focusInside(kept));

        edit(fixture, source("int a = 99;", "int b = 2;"));

        CodeBlock after = statement(fixture, 1);
        assertSame(kept, after, "the focused block's text did not change, so it should have survived");
        assertSame(keptNode, after.getUINode(),
                "and surviving is only worth anything if it kept the node it had already drawn");
    }

    @Test
    void the_highlight_names_the_block_once_focus_has_left() throws Exception {
        // The ordinary case, not a corner: every text editor in this layer commits on focus-lost, so the
        // re-parse is *caused by* focus leaving and there is usually no focused block by the time it runs.
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        renderInto(fixture);

        CodeBlock kept = statement(fixture, 1);
        Node keptNode = kept.getUINode();
        onFx(() -> {
            focusOutsideAnyBlock();
            fixture.state.setHighlightedBlock(kept);
        });

        edit(fixture, source("int a = 99;", "int b = 2;"));

        assertSame(keptNode, statement(fixture, 1).getUINode(),
                "with focus gone, the highlight is what still says where the user was");
    }

    @Test
    void a_block_outside_the_kept_subtree_is_rebuilt() throws Exception {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        renderInto(fixture);

        CodeBlock kept = statement(fixture, 1);
        Node otherNode = statement(fixture, 0).getUINode();
        onFx(() -> focusInside(kept));

        // The first statement is not edited at all, and is rebuilt anyway: the narrow policy allows exactly
        // one subtree, which is what keeps a bug here next to the thing the user was doing.
        edit(fixture, source("int a = 1;", "int b = 222;"));

        assertNotSame(otherNode, statement(fixture, 0).getUINode(),
                "the narrow policy keeps one subtree; every other block rebuilds as it always did");
    }

    @Test
    void a_render_with_no_highlight_and_no_focus_keeps_nothing() throws Exception {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        renderInto(fixture);

        Node beforeNode = statement(fixture, 1).getUINode();
        onFx(this::focusOutsideAnyBlock);

        edit(fixture, source("int a = 99;", "int b = 2;"));

        assertNotSame(beforeNode, statement(fixture, 1).getUINode(),
                "with nowhere named as where the user is, reuseForThisEdit answers NONE");
    }
}
