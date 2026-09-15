package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas keeps its scroll position across an edit — and, since 2026-09-15, <b>why the workaround that
 * makes that true is still needed</b>.
 *
 * <p>The plan for block reuse expected {@code handleBlocksUpdate}'s {@code vvalue} save/restore to become
 * dead code once subtrees survived a re-parse. It is not. Deleting it was tried, with this test in place,
 * and the canvas jumped to the top (<code>expected: 0.8 but was: 0.0</code>); replacing the
 * {@code clear()}-then-{@code add} with a single {@code setAll} did not help either.
 *
 * <p>The reason is structural and was designed in: <b>member blocks are never reused.</b>
 * {@code BlockConverter.parseRoot} builds {@code ClassBlock} and {@code MethodDeclarationBlock} fresh every
 * parse, so the node the canvas is handed is always a new one with no layout yet. The {@link ScrollPane}'s
 * scrollable range therefore still collapses, and {@code vvalue} is still clamped away with it — reuse made
 * the rebuild cheaper without making it stop happening.
 *
 * <p>So this test guards the restore rather than its absence. If it ever starts failing after somebody
 * deletes those two lines, this javadoc is the answer; removing them for real needs reusable members, which
 * is a separate decision with its own costs.
 *
 * <p>See {@code docs/refactor/30-block-reuse.md} §1 and §10.
 */
class CanvasScrollTest extends FxHeadlessTest {

    /** A program long enough that the canvas genuinely scrolls in a 400px viewport. */
    private static String source(String firstStatement) {
        StringBuilder sb = new StringBuilder("package com.mybot;\npublic class Subject {\n"
                + "    public void run() {\n"
                + "        " + firstStatement + "\n");
        for (int i = 1; i < 40; i++) sb.append("        int v").append(i).append(" = ").append(i).append(";\n");
        return sb.append("    }\n}\n").toString();
    }

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        stage.setScene(new Scene(root, 800, 400));
        stage.show();
    }

    /** Runs {@code work} on the FX thread and drains the two pulses a render is deferred behind. */
    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            work.run();
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread should have run the work");
        for (int i = 0; i < 2; i++) {
            CountDownLatch drained = new CountDownLatch(1);
            Platform.runLater(drained::countDown);
            assertTrue(drained.await(10, TimeUnit.SECONDS), "a deferred pulse should have run");
        }
    }

    @Test
    void an_edit_leaves_the_canvas_where_the_user_scrolled_it() throws Exception {
        EditorFixture fixture = new EditorFixture(source("int a = 1;"));

        EditorCanvas[] made = new EditorCanvas[1];
        onFx(() -> {
            made[0] = new EditorCanvas(fixture.context(), fixture.bus(),
                    false, "Subject", () -> {}, List::of, () -> {});
            root.getChildren().setAll(made[0].node());
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class,
                    made[0]::handleBlocksUpdate, false);
        });

        onFx(fixture::rerender);

        VBox column = made[0].node();
        ScrollPane scroll = (ScrollPane) column.getChildren().get(0);

        // The toolkit is shared with every other FX test in the run, so how many pulses it takes for this
        // stage to be sized is not ours to predict. Lay out until the pane genuinely scrolls, then scroll it.
        double scrolledTo = 0;
        for (int attempt = 0; attempt < 20 && scrolledTo <= 0.5; attempt++) {
            onFx(() -> {
                root.applyCss();
                root.layout();
                scroll.setVvalue(0.8);
            });
            scrolledTo = scroll.getVvalue();
        }
        assertTrue(scrolledTo > 0.5,
                "the fixture must be tall enough to scroll, or this test asserts nothing (was " + scrolledTo + ")");

        // An edit to the FIRST statement, so the whole program below it is re-rendered around a changed node.
        onFx(() -> fixture.bus().publish(
                new CoreApplicationEvents.CodeUpdatedEvent(source("int a = 99;"), fixture.state.getCurrentCode())));
        onFx(() -> {
            root.applyCss();
            root.layout();
        });

        assertEquals(scrolledTo, scroll.getVvalue(), 0.02,
                "the canvas jumped after an edit — the viewport must stay where the user put it");
    }

    @Test
    void the_root_member_is_rebuilt_on_every_edit_which_is_why_the_restore_stays() throws Exception {
        // The cause, asserted directly so the reason survives the next person reading handleBlocksUpdate.
        // Members are built in parseRoot and deliberately excluded from reuse, so the node the canvas is
        // handed is new every time and has no layout -- which is what collapses the scrollable range.
        EditorFixture fixture = new EditorFixture(source("int a = 1;"));
        fixture.context();

        List<javafx.scene.Node> seen = new java.util.ArrayList<>();
        onFx(() -> fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class,
                e -> seen.add(e.rootBlock().getUINode(fixture.context())), false));

        onFx(fixture::rerender);
        onFx(() -> fixture.bus().publish(
                new CoreApplicationEvents.CodeUpdatedEvent(source("int a = 99;"), fixture.state.getCurrentCode())));

        assertEquals(2, seen.size(), "both renders should have published a root");
        assertTrue(seen.get(0) != seen.get(1),
                "the root member is rebuilt, so the canvas always swaps in a node with no layout");
    }
}
