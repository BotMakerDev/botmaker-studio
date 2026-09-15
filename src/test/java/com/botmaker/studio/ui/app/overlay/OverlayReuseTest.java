package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD half of block reuse: an overlay row's widgets survive a re-parse when the block does.
 *
 * <p>The canvas got this when a surviving block kept its {@code uiNode}. The overlay rebuilt its widgets on
 * every pulse regardless — the same loss, on the surface a user most often edits <em>through</em>, since the
 * HUD is the only place a bot can be authored without leaving the game.
 *
 * <p>What is asserted is the thing that is easy to get wrong: the components are cached and the <b>row is
 * not</b>. A cached row would freeze the focus ring, the fold arrow and the reorder buttons onto whichever
 * row held them when the block was last drawn, and every one of those moves while the block stands still.
 *
 * <p>See {@code docs/refactor/30-block-reuse.md} §9.
 */
class OverlayReuseTest extends FxHeadlessTest {

    /** Two variable declarations, so one can change while the other does not. Both declare a spec. */
    private static String source(String first, String second) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        " + first + "\n"
                + "        " + second + "\n"
                + "    }\n"
                + "}\n";
    }

    private static CodeBlock statement(EditorFixture fixture, int index) {
        return fixture.body("run").getStatements().get(index);
    }

    /** Runs {@code work} on the FX thread, then drains the render the service defers behind it. */
    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            work.run();
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread should have run the work");
        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS), "the deferred render should have run");
    }

    /** A tree view over {@code fixture}, with callbacks that do nothing — no row is used here. */
    private static OverlayTreeView treeFor(EditorFixture fixture) throws Exception {
        OverlayTreeView[] made = new OverlayTreeView[1];
        onFx(() -> made[0] = new OverlayTreeView(
                fixture.context(),
                new OverlayTreeView.Callbacks(c -> {}, (s, b, i) -> {}, m -> {}, (s, b, i, d) -> {}, s -> {}),
                () -> {}));
        return made[0];
    }

    /** Draws {@code run()}'s body, with the caret wherever {@code cursorIndex} says (-1 for nowhere). */
    private static void draw(OverlayTreeView tree, EditorFixture fixture, int cursorIndex) throws Exception {
        BodyBlock body = fixture.body("run");
        onFx(() -> tree.render(
                List.of(body),
                cursorIndex < 0 ? null : new com.botmaker.studio.project.InsertionCursor(body, cursorIndex),
                stmt -> false));
    }

    /**
     * The rows the view just drew.
     *
     * <p>Reached through the panel's own children rather than a lookup: the rows {@code VBox} carries no id
     * or style class, and giving it one only so a test could find it would be a production change made for
     * a test.
     */
    private static List<Node> rowsOf(OverlayTreeView tree) {
        VBox panel = tree.node();
        ScrollPane scroll = (ScrollPane) panel.getChildren().get(1);
        return ((Pane) scroll.getContent()).getChildrenUnmodifiable();
    }

    private static void edit(EditorFixture fixture, String newSource) throws Exception {
        onFx(() -> fixture.bus().publish(
                new CoreApplicationEvents.CodeUpdatedEvent(newSource, fixture.state.getCurrentCode())));
    }

    @Test
    void an_unchanged_block_keeps_its_hud_widgets_across_an_edit_elsewhere() throws Exception {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        OverlayTreeView tree = treeFor(fixture);
        draw(tree, fixture, -1);

        CodeBlock kept = statement(fixture, 1);
        List<Node> keptWidgets = ((AbstractCodeBlock) kept).getCompactNodes();
        assertFalse(keptWidgets == null || keptWidgets.isEmpty(),
                "the fixture's second statement should declare a spec, or this test asserts nothing");

        edit(fixture, source("int a = 99;", "int b = 2;"));
        draw(tree, fixture, -1);

        CodeBlock after = statement(fixture, 1);
        assertSame(kept, after, "the block is unchanged, so this test is only meaningful if it survived");
        assertSame(keptWidgets, ((AbstractCodeBlock) after).getCompactNodes(),
                "and surviving is only worth anything if the HUD kept the widgets it had already drawn");
    }

    @Test
    void the_widgets_are_still_in_the_row_the_view_redrew() throws Exception {
        // The cache is worth nothing if the redrawn row does not put the kept widgets back on screen.
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        OverlayTreeView tree = treeFor(fixture);
        draw(tree, fixture, -1);

        List<Node> keptWidgets = List.copyOf(((AbstractCodeBlock) statement(fixture, 1)).getCompactNodes());

        edit(fixture, source("int a = 99;", "int b = 2;"));
        draw(tree, fixture, -1);

        Node row = rowsOf(tree).get(1);
        List<Node> drawn = ((Pane) row).getChildrenUnmodifiable();
        for (Node widget : keptWidgets) {
            assertTrue(drawn.contains(widget), "a kept widget should be back in the redrawn row");
            assertSame(row, widget.getParent(), "and reparented onto it, not left under the discarded row");
        }
    }

    @Test
    void a_block_whose_text_changed_gets_fresh_hud_widgets() throws Exception {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        OverlayTreeView tree = treeFor(fixture);
        draw(tree, fixture, -1);

        List<Node> before = ((AbstractCodeBlock) statement(fixture, 0)).getCompactNodes();

        edit(fixture, source("int a = 99;", "int b = 2;"));
        draw(tree, fixture, -1);

        assertNotSame(before, ((AbstractCodeBlock) statement(fixture, 0)).getCompactNodes(),
                "a rebuilt block is a new object, so it must start with no cached widgets");
    }

    @Test
    void the_row_itself_is_rebuilt_so_the_caret_can_move_onto_a_kept_block() throws Exception {
        // The reason only the components are cached. Nothing about this block changes -- the caret moves --
        // and a cached row would keep drawing the focus ring where it was.
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        OverlayTreeView tree = treeFor(fixture);
        draw(tree, fixture, 0);

        Node rowBefore = rowsOf(tree).get(1);
        String styleBefore = rowBefore.getStyle();

        draw(tree, fixture, 1);

        Node rowAfter = rowsOf(tree).get(1);
        assertNotSame(rowBefore, rowAfter, "the row is a function of the caret, so it is drawn again");
        assertNotEquals(styleBefore, rowAfter.getStyle(), "and the focus ring follows the caret onto it");
        assertTrue(rowAfter.getStyle().contains("#4a90e2"), "the focused row carries the highlight border");
    }
}
