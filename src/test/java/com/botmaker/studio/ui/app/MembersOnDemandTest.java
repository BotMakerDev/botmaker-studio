package com.botmaker.studio.ui.app;

import com.botmaker.studio.blocks.MemberPlaceholder;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas draws the members of a type near its viewport and a blank for each of the others (2026-10-18), and
 * every path that needs a member's node draws it first: a reveal (the find bar, an error, a Structure row), a
 * scroll, an error or a highlight set before the member was drawn.
 */
class MembersOnDemandTest extends FxHeadlessTest {

    private static final int CONSTANTS = 200;

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        Scene scene = new Scene(root, 800, 400);
        // Styled as Studio is, so a member's drawn height is the one a blank guesses.
        ThemedWindows.addStylesheet(scene);
        ThemedWindows.applyThemeClass(root);
        stage.setScene(scene);
        stage.show();
    }

    /** Many one-line constants, then one method far down. */
    private static String source() {
        StringBuilder sb = new StringBuilder("package com.mybot;\npublic class Subject {\n");
        for (int i = 0; i < CONSTANTS; i++) sb.append("    public static final int C").append(i).append(" = ").append(i).append(";\n");
        return sb.append("    public void run() {\n        int far = 1;\n    }\n}\n").toString();
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

    /** The fixture drawn in a canvas, its stage laid out. */
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

    private static CodeBlock constant(EditorFixture fixture, int index) {
        return fixture.state.getNodeToBlockMap().entrySet().stream()
                .filter(e -> e.getKey() instanceof FieldDeclaration field && ((VariableDeclarationFragment)
                        field.fragments().getFirst()).getName().getIdentifier().equals("C" + index))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
    }

    private static CodeBlock farStatement(EditorFixture fixture) {
        return fixture.state.getNodeToBlockMap().entrySet().stream()
                .filter(e -> e.getKey() instanceof VariableDeclarationStatement)
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
    }

    private static long blanks(EditorCanvas canvas) {
        return canvas.node().lookupAll("." + MemberPlaceholder.STYLE_CLASS).size();
    }

    private static boolean inScene(Node node) {
        return node != null && node.getScene() != null;
    }

    @Test
    void only_the_members_near_the_viewport_are_drawn() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        onFx(() -> {
            assertTrue(inScene(constant(fixture, 0).getUINode()), "the first constant is in view and drawn");
            assertNull(constant(fixture, CONSTANTS - 1).getUINode(), "the last is far below and is not");
            assertNull(farStatement(fixture).getUINode(), "nor is the method after it");
            assertTrue(blanks(canvas) > CONSTANTS / 2, "most members are blanks, was " + blanks(canvas));
        });
    }

    @Test
    void a_reveal_draws_a_far_member_and_scrolls_to_it() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        CodeBlock far = farStatement(fixture);
        onFx(() -> canvas.revealBlock(far, false));   // as the find bar, an error row and a Structure row do
        ScrollPane scroll = (ScrollPane) canvas.node().lookup(".code-scroll-pane");
        onFx(() -> {
            assertTrue(inScene(far.getUINode()), "the revealed block is drawn");
            assertTrue(scroll.getVvalue() > 0.9, "and scrolled to, near the end (was " + scroll.getVvalue() + ")");
            assertTrue(inScene(constant(fixture, CONSTANTS - 1).getUINode()), "with the members around it drawn");
            assertNull(constant(fixture, CONSTANTS / 2).getUINode(), "but not those it scrolled past");
        });
    }

    @Test
    void a_block_an_edit_kept_is_drawn_again_when_revealed_though_its_member_is_a_blank() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        ScrollPane scroll = (ScrollPane) canvas.node().lookup(".code-scroll-pane");
        onFx(() -> canvas.revealBlock(farStatement(fixture), false));
        onFx(() -> scroll.setVvalue(0));
        // An edit to the first constant: run() is a blank again, while its statement's block keeps its node.
        onFx(() -> fixture.bus().publish(new CoreApplicationEvents.CodeUpdatedEvent(
                source().replace("C0 = 0;", "C0 = 99;"), fixture.state.getCurrentCode())));
        CodeBlock kept = farStatement(fixture);
        onFx(() -> {
            assertNotNull(kept.getUINode(), "the edit reused the statement's block, node and all");
            assertNull(kept.getUINode().getScene(), "with its member a blank, that node is out of the scene");
            canvas.revealBlock(kept, false);
        });
        onFx(() -> assertTrue(inScene(kept.getUINode()), "a reveal draws its member again"));
    }

    @Test
    void an_error_and_a_highlight_set_before_a_member_is_drawn_show_once_it_is() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        CodeBlock far = constant(fixture, CONSTANTS - 1);
        onFx(() -> {
            far.setError("broken");
            far.highlight();
            assertNull(far.getUINode());
        });
        onFx(() -> canvas.revealBlock(far, false));
        onFx(() -> {
            Node node = far.getUINode();
            assertNotNull(node);
            assertTrue(node.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("error")),
                    "the error is on the node drawn after it");
            assertTrue(node.getPseudoClassStates().stream()
                    .anyMatch(p -> p.getPseudoClassName().equals("highlighted")), "and the highlight");
        });
    }

    @Test
    void scrolling_draws_what_comes_into_view() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        ScrollPane scroll = (ScrollPane) canvas.node().lookup(".code-scroll-pane");
        CodeBlock middle = constant(fixture, CONSTANTS / 2);
        assertNull(middle.getUINode());
        onFx(() -> scroll.setVvalue(0.5));
        onFx(() -> assertTrue(inScene(middle.getUINode()), "the constant halfway down is drawn once scrolled to"));
    }

    @Test
    void a_blank_guesses_a_members_height_within_a_third() throws Exception {
        EditorFixture fixture = new EditorFixture(source());
        EditorCanvas canvas = drawn(fixture);
        CodeBlock far = farStatement(fixture);
        onFx(() -> canvas.revealBlock(far, false));
        onFx(() -> {
            double drawnHeight = constant(fixture, CONSTANTS - 1).getUINode().getLayoutBounds().getHeight();
            double guess = MemberPlaceholder.estimate(constant(fixture, CONSTANTS - 1).getAstNode());
            CodeBlock method = fixture.state.getNodeToBlockMap().get(far.getAstNode().getParent().getParent());
            System.out.printf("[members-on-demand] a constant draws %.1f px, its blank guesses %.1f; a 3-line "
                            + "method %.1f, guessed %.1f%n", drawnHeight, guess,
                    method.getUINode().getLayoutBounds().getHeight(), MemberPlaceholder.estimate(method.getAstNode()));
            assertTrue(Math.abs(drawnHeight - guess) < drawnHeight / 3,
                    "a constant draws " + drawnHeight + " px and its blank guesses " + guess);
            double methodHeight = method.getUINode().getLayoutBounds().getHeight();
            assertTrue(Math.abs(methodHeight - MemberPlaceholder.estimate(method.getAstNode())) < methodHeight / 3,
                    "a 3-line method draws " + methodHeight + " px");
        });
    }
}
