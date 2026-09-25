package com.botmaker.studio.ui.fx;

import com.botmaker.studio.core.render.StackJoints;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.dnd.InsertionSeam;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.theme.BlockFont;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blocks stack touching: the seams between them take no room, each seam's hit strip straddles the join, a
 * statement carries a notch and a tab, and the value buttons say "+" to fill and "▾" to change.
 */
class GluedStackTest extends FxHeadlessTest {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    private VBox rendered(EditorFixture f) {
        AtomicReference<VBox> body = new AtomicReference<>();
        interact(() -> {
            VBox node = (VBox) f.body("run").getUINode(f.context());
            VBox root = new VBox(node);
            Scene scene = new Scene(root, 500, 400);
            scene.getStylesheets().add(getClass().getResource("/css/blocks.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            body.set(node);
        });
        return body.get();
    }

    private static EditorFixture threeStatements() {
        return new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                    }
                }
                """);
    }

    @Test
    void statementsTouchAndEachSeamStraddlesItsJoin() {
        VBox body = rendered(threeStatements());
        List<Pane> seams = new ArrayList<>();
        List<Node> statements = new ArrayList<>();
        for (Node child : body.getChildren()) {
            if (child.getStyleClass().contains("block-seam")) seams.add((Pane) child);
            else if (child.getStyleClass().contains("block")) statements.add(child);
        }
        assertEquals(4, seams.size());
        assertEquals(3, statements.size(), statements.stream().map(n -> n.getClass().getSimpleName() + n.getStyleClass()).toList().toString());
        for (Pane seam : seams) assertEquals(0.0, seam.getHeight(), 0.01, "a seam takes no room");
        for (int i = 1; i < statements.size(); i++) {
            // Layout edges, not visual bounds: the tab hangs 4px past the block's own bottom by design.
            Region above = (Region) statements.get(i - 1);
            Bounds below = statements.get(i).getLayoutBounds();
            double belowTop = statements.get(i).getLayoutY() + below.getMinY();
            assertEquals(above.getLayoutY() + above.getHeight(), belowTop, 0.5,
                    "statement " + i + " touches the one above");

            Region hit = (Region) seams.get(i).lookup(".seam-hit");
            assertNotNull(hit);
            Bounds strip = seams.get(i).localToParent(hit.getBoundsInParent());
            assertTrue(strip.getMinY() < belowTop && strip.getMaxY() > belowTop,
                    "the hit strip of seam " + i + " straddles the join: " + strip);
            assertEquals(InsertionSeam.HIT_HEIGHT, strip.getHeight(), 0.01);
        }
    }

    @Test
    void aStatementHasANotchAboveAndATabBelow() {
        VBox body = rendered(threeStatements());
        Node first = body.getChildren().stream()
                .filter(n -> !n.getStyleClass().contains("block-seam")).findFirst().orElseThrow();
        Region notch = (Region) ((Parent) first).lookup("." + StackJoints.NOTCH);
        Region tab = (Region) ((Parent) first).lookup("." + StackJoints.TAB);
        assertNotNull(notch);
        assertNotNull(tab);
        assertEquals(0.0, notch.getLayoutY(), 0.01);
        assertEquals(((Region) first).getHeight(), tab.getLayoutY(), 0.5, "the tab hangs from the bottom edge");
        assertTrue(first.getViewOrder() < body.getChildren().stream()
                .filter(n -> !n.getStyleClass().contains("block-seam")).skip(1).findFirst().orElseThrow()
                .getViewOrder(), "a block is drawn ahead of the one below, so its tab lies over their join");
    }

    @Test
    void aStatementInABodyIsSquareSoNoCanvasShowsAtAJoin() {
        VBox body = rendered(nested());
        for (Node statement : statementsOf(body)) {
            assertEquals(javafx.scene.layout.CornerRadii.EMPTY, radii(statement),
                    statement.getClass().getSimpleName() + " touches something coloured at every corner");
        }
        Parent loop = (Parent) statementsOf(body).get(1);
        Node inMouth = statementsOf((VBox) loop.lookup(".bc-body").lookup(".body-block")).getFirst();
        assertEquals(javafx.scene.layout.CornerRadii.EMPTY, radii(inMouth), "the header and the arm touch it");
    }

    @Test
    void theSeamsPlusSitsBesideTheJointWhereverThePointerEnters() {
        VBox body = rendered(threeStatements());
        Pane seam = (Pane) body.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("block-seam")).skip(1).findFirst().orElseThrow();
        Button plus = (Button) seam.getChildren().stream().filter(n -> n instanceof Button).findFirst().orElseThrow();
        interact(() -> seam.fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED,
                seam.getWidth() - 5, 0, 0, 0, javafx.scene.input.MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, false, null)));

        assertTrue(plus.isVisible());
        assertEquals(InsertionSeam.PLUS_X, plus.getLayoutX(), 0.01,
                "entering at the right end must not put the \"+\" over the delete button");
    }

    private static javafx.scene.layout.CornerRadii radii(Node block) {
        return ((Region) block).getBackground().getFills().getFirst().getRadii();
    }

    private static EditorFixture nested() {
        return new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        boolean x = true;
                        while (x) {
                            int a = 1;
                        }
                        if (x) {
                            int b = 2;
                        } else if (!x) {
                            int c = 3;
                        } else {
                            int d = 4;
                        }
                    }
                }
                """);
    }

    private static List<Node> statementsOf(VBox body) {
        return body.getChildren().stream().filter(n -> n.getStyleClass().contains("block")).toList();
    }

    @Test
    void aCsMouthHoldsItsStackAgainstTheArmAndUnderTheHeader() {
        VBox body = rendered(nested());
        Parent loop = (Parent) statementsOf(body).get(1);
        Region mouth = (Region) loop.lookup(".bc-body");
        VBox inner = (VBox) mouth.lookup(".body-block");
        Node first = statementsOf(inner).getFirst();
        Bounds inMouth = mouth.sceneToLocal(first.localToScene(first.getLayoutBounds()));
        assertEquals(12.0, inMouth.getMinX(), 0.5, "the stack sits against the arm: " + inMouth);
        assertEquals(0.0, inMouth.getMinY(), 0.5, "the stack sits under the header: " + inMouth);
        assertTrue(first.getStyleClass().contains(com.botmaker.studio.core.BodyBlock.FIRST_STYLE_CLASS));
        assertTrue(!((Parent) first).lookup("." + StackJoints.NOTCH).isVisible(),
                "the first block's dent has no tab above it to fit");
    }

    @Test
    void anElseIfLinkIsTheChainNotAPieceInIt() {
        VBox body = rendered(nested());
        Parent chain = (Parent) statementsOf(body).get(2);
        Node link = chain.lookupAll("." + com.botmaker.studio.blocks.flow.IfBlock.ELSE_IF_LINK_STYLE_CLASS)
                .stream().findFirst().orElseThrow();
        assertTrue(((Parent) link).getChildrenUnmodifiable().stream()
                        .noneMatch(n -> n.getStyleClass().contains(StackJoints.NOTCH)
                                || n.getStyleClass().contains(StackJoints.TAB)),
                "an else-if link carries no joints");
        assertEquals(0.0, ((Region) link).getPadding().getBottom(), 0.01, "the chain's first if closes it");
    }

    @Test
    void fillAndChangeAreTwoGlyphs() {
        AtomicReference<Button> add = new AtomicReference<>();
        AtomicReference<Button> change = new AtomicReference<>();
        interact(() -> {
            add.set(BlockUIComponents.createAddButton(e -> {}));
            change.set(BlockUIComponents.createChangeButton(e -> {}));
        });
        assertEquals("+", add.get().getText());
        assertEquals("▾", change.get().getText());
        assertTrue(change.get().getStyleClass().contains("expression-change-button"));
    }

    @Test
    void aBlockFontRoundTripsThroughItsId() {
        assertEquals(BlockFont.NUNITO, BlockFont.fromId(BlockFont.NUNITO.id()));
        assertEquals(BlockFont.SYSTEM, BlockFont.fromId("system"));
        assertEquals(new BlockFont("DejaVu Sans"), BlockFont.fromId(new BlockFont("DejaVu Sans").id()));
        assertEquals(BlockFont.DEFAULT, BlockFont.fromId("garbage"));
        assertTrue(BlockFont.NUNITO.canvasStyle().contains("\"Nunito\""));
        assertTrue(!BlockFont.SYSTEM.canvasStyle().contains("font-family"));
    }

    @Test
    void theBundledNunitoLoads() {
        interact(BlockFont::loadBundled);
        assertTrue(javafx.scene.text.Font.getFamilies().contains("Nunito"), "Nunito is registered");
    }
}
