package com.botmaker.studio.ui.app;

import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Switching a call's method from its dropdown, again and again, with other edits in between — the way a user
 * does it. Reported 2026-09-26: it "worked a bit and then stopped working".
 */
class MethodSwitchTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public static void run() {
                    int a = 1;
                    int m = Subject.max(1, 2);
                    int b = 2;
                }
                public static int max(int x, int y) { return x; }
                public static int min(int x, int y) { return y; }
                public static int abs(int x) { return x; }
            }
            """;

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        stage.setScene(new Scene(root, 900, 500));
        stage.show();
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 3; i++) {
            CountDownLatch drained = new CountDownLatch(1);
            Platform.runLater(drained::countDown);
            assertTrue(drained.await(10, TimeUnit.SECONDS));
        }
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static MethodInvocationBlock call(EditorFixture fixture) {
        for (CodeBlock block : fixture.state.getNodeToBlockMap().values()) {
            if (block instanceof MethodInvocationBlock mib
                    && List.of("max", "min", "abs").contains(mib.getMethodName())) return mib;
        }
        throw new AssertionError("no call block in:\n" + fixture.state.getCurrentCode());
    }

    /** The fixture's source on a live canvas, rendered once. */
    private EditorFixture opened() throws Exception {
        EditorFixture fixture = new EditorFixture(SOURCE);
        onFx(() -> {
            EditorCanvas canvas = new EditorCanvas(fixture.context(), fixture.bus(), false, "Subject", () -> {},
                    List::of, () -> {});
            root.getChildren().setAll(canvas.node());
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class,
                    canvas::handleBlocksUpdate, false);
        });
        onFx(fixture::rerender);
        return fixture;
    }

    private static CodeBlock firstArgument(EditorFixture fixture) {
        var mi = (org.eclipse.jdt.core.dom.MethodInvocation) call(fixture).getAstNode();
        return fixture.state.getNodeToBlockMap().get((org.eclipse.jdt.core.dom.ASTNode) mi.arguments().getFirst());
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> methodSelector(Node ui, String current) {
        List<ComboBox<String>> combos = new ArrayList<>();
        collect(ui, combos);
        return combos.stream()
                .filter(c -> current.equals(c.getValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method selector showing " + current));
    }

    @SuppressWarnings("unchecked")
    private static void collect(Node node, List<ComboBox<String>> out) {
        if (node instanceof ComboBox<?> combo) out.add((ComboBox<String>) combo);
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(c -> collect(c, out));
    }

    private void pick(EditorFixture fixture, String from, String to) throws Exception {
        onFx(() -> {
            MethodInvocationBlock block = call(fixture);
            ComboBox<String> selector = methodSelector(block.getUINode(fixture.context()), from);
            assertTrue(selector.getItems().contains(to), to + " should be offered, got " + selector.getItems());
            selector.setValue(to);
        });
        String code = fixture.state.getCurrentCode();
        assertTrue(code.contains("int m = " + to + "(") || code.contains("int m = Subject." + to + "("),
                "after picking " + to + ":\n" + code);
    }

    /**
     * The cause of the report: block reuse kept an argument whose own text had not changed, and with it the
     * editor it had been drawn with — chosen for the call it sat in. Switching {@code seconds} to
     * {@code milliseconds} left {@code 1} untouched, so the slot went on being {@code seconds}' slot. An
     * argument is drawn for its call, so a call that changed draws its arguments again.
     */
    @Test
    void anArgumentIsDrawnAgainWhenItsCallChanges() throws Exception {
        EditorFixture fixture = opened();
        CodeBlock[] before = new CodeBlock[1];
        onFx(() -> before[0] = firstArgument(fixture));

        pick(fixture, "max", "min");

        CodeBlock[] after = new CodeBlock[1];
        onFx(() -> after[0] = firstArgument(fixture));
        assertNotNull(after[0]);
        assertNotSame(before[0], after[0], "the argument kept the widget it was drawn with for max(…)");
    }

    @Test
    void theMethodCanBeSwitchedRepeatedlyAcrossOtherEdits() throws Exception {
        EditorFixture fixture = opened();

        pick(fixture, "max", "min");
        pick(fixture, "min", "abs");
        // An unrelated edit, re-rendering around the call with block reuse.
        onFx(() -> fixture.bus().publish(new CoreApplicationEvents.CodeUpdatedEvent(
                fixture.state.getCurrentCode().replace("int a = 1;", "int a = 5;"),
                fixture.state.getCurrentCode())));
        pick(fixture, "abs", "max");
        pick(fixture, "max", "min");
        onFx(() -> fixture.bus().publish(new CoreApplicationEvents.CodeUpdatedEvent(
                fixture.state.getCurrentCode().replace("int b = 2;", "int b = 7;"),
                fixture.state.getCurrentCode())));
        pick(fixture, "min", "max");
    }
}
