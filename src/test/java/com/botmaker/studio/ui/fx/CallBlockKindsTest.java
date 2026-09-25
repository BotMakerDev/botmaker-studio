package com.botmaker.studio.ui.fx;

import com.botmaker.studio.blocks.func.ExternalCallBlock;
import com.botmaker.studio.blocks.func.ProjectCallBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.parser.BlockReuse;
import com.botmaker.studio.parser.EditorFixture;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call is one of two blocks: "call ‹method›" for the bot's own, "use ‹owner› …" for anybody else's — and both
 * are FUNCTIONS blocks, painted like every other block rather than left unfilled (the bot's own) or framed
 * apart (a plugin's).
 */
class CallBlockKindsTest extends FxHeadlessTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                static int helper() { return 1; }
                public void run() {
                    helper();
                    Math.max(1, 2);
                }
            }
            """;

    private List<Node> rendered(EditorFixture f) {
        AtomicReference<VBox> body = new AtomicReference<>();
        interact(() -> {
            VBox node = (VBox) f.body("run").getUINode(f.context());
            VBox root = new VBox(node);
            Scene scene = new Scene(root, 600, 300);
            scene.getStylesheets().add(getClass().getResource("/css/blocks.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            body.set(node);
        });
        return body.get().getChildren().stream().filter(n -> n.getStyleClass().contains("block")).toList();
    }

    @Test
    void theBotsOwnCallSaysCallAndIsAFilledFunctionsBlock() {
        EditorFixture f = new EditorFixture(SOURCE);
        assertInstanceOf(ProjectCallBlock.class, f.body("run").getStatements().getFirst());
        Node call = rendered(f).getFirst();

        assertTrue(call.getStyleClass().contains("category-functions"), call.getStyleClass().toString());
        assertTrue(labels(call).contains("call"), labels(call).toString());
        assertNull(((Parent) call).lookup(".call-owner-badge"), "the bot's own call names no owner");
    }

    @Test
    void aJdkCallSaysUseJavaAndLooksLikeEveryOtherBlock() {
        EditorFixture f = new EditorFixture(SOURCE);
        assertInstanceOf(ExternalCallBlock.class, f.body("run").getStatements().get(1));
        Node call = rendered(f).get(1);

        assertTrue(call.getStyleClass().contains("category-functions"), call.getStyleClass().toString());
        assertTrue(labels(call).contains("use"), labels(call).toString());
        assertEquals("Java", ((Label) ((Parent) call).lookup(".call-owner-badge")).getText());
        assertTrue(call.getStyleClass().stream().noneMatch(c -> c.startsWith("sdk-")), "no plugin frame of its own");
    }

    @Test
    void aCallMovedOntoAnotherOwnersClassIsRebuiltAsTheOtherKind() {
        // Every surviving block is offered back: the call's text changed, so its block must not be one of them.
        EditorFixture f = new EditorFixture(SOURCE);
        String moved = SOURCE.replace("helper();", "Math.abs(1);");
        BlockReuse reuse = BlockReuse.of(f.state.getNodeToBlockMap(), f.source, false, block -> true);

        com.botmaker.studio.core.CodeBlock root = f.reparse(moved, reuse).root();
        List<StatementBlock> calls = new ArrayList<>();
        collectCalls(root, calls);

        assertInstanceOf(ExternalCallBlock.class, calls.getFirst(), "the kind follows the class the call is on");
    }

    private static void collectCalls(com.botmaker.studio.core.CodeBlock block, List<StatementBlock> out) {
        if (block instanceof com.botmaker.studio.blocks.func.MethodInvocationBlock call) out.add(call);
        if (block instanceof com.botmaker.studio.core.BlockWithChildren parent) {
            for (com.botmaker.studio.core.CodeBlock child : parent.getChildren()) collectCalls(child, out);
        }
    }

    private static List<String> labels(Node node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Label label) out.add(label.getText());
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, out);
        }
    }
}
