package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.overlay.ProbeResult;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A row's probe marker: hidden until its call has an answer, then ✓ ✗ or ? with the probe's line as its
 * tooltip — drawn from the last answer when the list is drawn, and updated in place when one arrives.
 */
class OverlayProbeMarkersTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public void run() {
                    int a = 1;
                    int b = 2;
                }
            }
            """;

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            work.run();
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread should have run the work");
    }

    /** The probe marker of the drawn row at {@code index}. */
    private static Label marker(OverlayTreeView tree, int index) {
        Pane row = (Pane) ((Pane) tree.node().getContent()).getChildrenUnmodifiable().get(index);
        for (Node child : row.getChildrenUnmodifiable()) {
            if (child instanceof Label label && label.getStyleClass().contains("overlay-probe")) return label;
        }
        throw new AssertionError("row " + index + " has no probe marker");
    }

    @Test
    void aRowShowsItsLastAnswerAndTakesANewOneInPlace() throws Exception {
        EditorFixture fixture = new EditorFixture(SOURCE);
        BodyBlock body = fixture.body("run");
        List<StatementBlock> statements = body.getStatements();
        ProbeResult found = ProbeResult.found("found 0.94 at 412,230", null);
        OverlayTreeView[] tree = new OverlayTreeView[1];
        onFx(() -> {
            tree[0] = new OverlayTreeView(fixture.context(),
                    new OverlayTreeView.Callbacks(c -> {}, (s, b, i) -> {}, m -> {}, (s, b, i, d) -> {}, s -> {}));
            tree[0].setProbes(stmt -> stmt == statements.get(0) ? found : null);
            tree[0].render(List.of(body), null, stmt -> false);
        });

        Label first = marker(tree[0], 0);
        assertTrue(first.isVisible());
        assertEquals("✓", first.getText());
        assertTrue(first.getStyleClass().contains("overlay-probe-found"));
        assertEquals("found 0.94 at 412,230", first.getTooltip().getText());

        Label second = marker(tree[0], 1);
        assertFalse(second.isVisible() || second.isManaged(), "a row with no answer takes no room");

        onFx(() -> tree[0].showProbe(statements.get(1), ProbeResult.missing("not found — best 0.41, needs 0.80")));
        assertTrue(second.isVisible());
        assertEquals("✗", second.getText());
        assertTrue(second.getStyleClass().contains("overlay-probe-missing"));

        onFx(() -> tree[0].showProbe(statements.get(1), ProbeResult.unknown("")));
        assertEquals("?", second.getText());
        assertFalse(second.getStyleClass().contains("overlay-probe-missing"), "one state's class at a time");
        assertEquals("Cannot tell", second.getTooltip().getText(), "an answer with no line says its state");
    }
}
