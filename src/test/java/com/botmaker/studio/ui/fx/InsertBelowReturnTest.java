package com.botmaker.studio.ui.fx;

import com.botmaker.studio.parser.EditorFixture;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "+" under a body's last statement, when that statement is a {@code return}: every activity body and every
 * non-void function ends with one, and a block written after it is unreachable code javac refuses. That seam
 * lands its block just above the return instead, so the bottom of a function is somewhere a block can go.
 */
class InsertBelowReturnTest extends FxHeadlessTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    @Test
    void theLastPlusOfABodyEndingInReturnInsertsAboveTheReturn() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public int run() {
                        int d = 1;
                        return d;
                    }
                }
                """);
        Pane last = seamsOf(render(f)).getLast();
        Button plus = plusOf(last);
        assertTrue(tooltipOf(plus).contains("before the return"), tooltipOf(plus));

        interact(plus::fire);
        ContextMenu menu = (ContextMenu) plus.getUserData();
        assertNotNull(menu, "the '+' opens the statement menu");
        MenuItem print = menu.getItems().stream().filter(InsertBelowReturnTest::isPrint).findFirst().orElseThrow();
        interact(print::fire);
        interact(menu::hide);

        assertNotNull(f.lastCode, "the insert was refused: " + f.statusMessages);
        assertTrue(f.lastCode.contains("int d = 1;\n        System.out.println(\"\");\n        return d;"), f.lastCode);
    }

    @Test
    void aBodyNotEndingInAJumpStillInsertsAtTheEnd() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        int d = 1;
                    }
                }
                """);
        Pane last = seamsOf(render(f)).getLast();
        Button plus = plusOf(last);
        assertEquals("Insert a block here", tooltipOf(plus));

        interact(plus::fire);
        ContextMenu menu = (ContextMenu) plus.getUserData();
        MenuItem print = menu.getItems().stream().filter(InsertBelowReturnTest::isPrint).findFirst().orElseThrow();
        interact(print::fire);
        interact(menu::hide);

        assertTrue(f.lastCode.contains("int d = 1;\n        System.out.println(\"\");\n    }"), f.lastCode);
    }

    private Node render(EditorFixture f) {
        Node[] out = new Node[1];
        interact(() -> {
            Node node = f.root.getUINode(f.context());
            VBox root = new VBox(node);
            Scene scene = new Scene(new ScrollPane(root), 700, 500);
            scene.getStylesheets().add(getClass().getResource("/css/blocks.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            out[0] = root;
        });
        return out[0];
    }

    private static List<Pane> seamsOf(Node node) {
        List<Pane> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<Pane> out) {
        if (node.getStyleClass().contains("block-seam") && node instanceof Pane seam) out.add(seam);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    private static Button plusOf(Pane seam) {
        return (Button) seam.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof Button).findFirst().orElseThrow();
    }

    private static String tooltipOf(Button plus) {
        Tooltip tip = (Tooltip) plus.getProperties().get("javafx.scene.control.Tooltip");
        return tip == null ? "" : tip.getText();
    }

    private static boolean isPrint(MenuItem item) {
        if ("Print".equals(item.getText())) return true;
        return item instanceof CustomMenuItem custom && containsText(custom.getContent(), "Print");
    }

    private static boolean containsText(Node node, String text) {
        if (node instanceof Labeled labeled && text.equals(labeled.getText())) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) if (containsText(child, text)) return true;
        }
        return false;
    }
}
