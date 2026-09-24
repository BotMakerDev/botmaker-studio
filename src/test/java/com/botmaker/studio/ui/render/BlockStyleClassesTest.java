package com.botmaker.studio.ui.render;

import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.app.BlockGalleryTest;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classes the stylesheet styles by are on the nodes it expects — the contract between
 * {@code AbstractCodeBlock.getUINode} and {@code blocks.css}. Every rule in BLOCK STYLE selects on these, so a
 * block that lost its shape class would lose its outline with no error anywhere.
 */
class BlockStyleClassesTest extends FxHeadlessTest {

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    private static List<Node> all(Node from, Predicate<Node> keep) {
        List<Node> out = new ArrayList<>();
        if (keep.test(from)) out.add(from);
        if (from instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) out.addAll(all(child, keep));
        }
        return out;
    }

    private static Predicate<Node> has(String... classes) {
        return n -> n.getStyleClass().containsAll(List.of(classes));
    }

    @Test
    void everyBlockCarriesItsShapeAndCategory() throws Exception {
        EditorFixture fixture = new EditorFixture(BlockGalleryTest.PROGRAM);
        Node[] drawn = new Node[1];
        onFx(() -> {
            drawn[0] = fixture.root.getUINode(fixture.context());
            root.getChildren().setAll(drawn[0]);
        });
        Node canvas = drawn[0];

        // One hat, three C-blocks plus the else-if link, and stack blocks for the lines.
        assertEquals(1, all(canvas, has("block", "shape-hat", "category-functions")).size(), "the method is a hat");
        assertEquals(4, all(canvas, has("block", "shape-c", "block-category")).size(),
                "while, if, its else-if link and for-each are C-blocks");
        assertEquals(2, all(canvas, has("block", "shape-c", "category-loops")).size(),
                "the while and the for-each are loop-coloured Cs");
        assertTrue(all(canvas, has("block", "shape-stack", "category-output")).size() >= 3, "each print is a stack block");
        assertFalse(all(canvas, has("block", "shape-boolean")).isEmpty(), "the conditions are booleans");
        assertFalse(all(canvas, has("block", "shape-reporter")).isEmpty(), "the names and the sum are reporters");

        // Every painted block has exactly one category.
        for (Node block : all(canvas, has("block-category"))) {
            long categories = block.getStyleClass().stream().filter(c -> c.startsWith("category-")).count();
            assertEquals(1, categories, "one category per painted block: " + block.getStyleClass());
            assertTrue(block.getStyleClass().contains("block"), "a painted block has an outline: " + block.getStyleClass());
        }

        // What has no outline has no block class: a statement list, a typed-in value.
        assertTrue(all(canvas, has("body-block")).stream().noneMatch(has("block")), "a body is not a block");
        for (Node field : all(canvas, n -> n instanceof TextField)) {
            Node literalRoot = field.getParent();
            assertFalse(literalRoot.getStyleClass().contains("shape-reporter"),
                    "a typed-in value is the slot's well, not a reporter: " + literalRoot.getStyleClass());
        }

        // Component classes: keywords are labels, C-block bodies are bodies — and a block used as a component
        // (the else-if link) is styled as the block it is, never as a body.
        assertFalse(all(canvas, n -> n instanceof Label l && "while".equals(l.getText())
                && l.getStyleClass().contains("bc-label")).isEmpty(), "a keyword is a bc-label");
        // while, if-then, else-if-then, else, for-each: five holes, each a direct child of its C.
        assertEquals(5, all(canvas, has("bc-body")).stream()
                        .filter(n -> n.getParent() != null && n.getParent().getStyleClass().contains("shape-c"))
                        .count(),
                "each C-block's body is a bc-body directly under the C, which is what the hole rule selects");
        assertTrue(all(canvas, has("bc-body", "block")).isEmpty(), "no block is also a body");
    }
}
