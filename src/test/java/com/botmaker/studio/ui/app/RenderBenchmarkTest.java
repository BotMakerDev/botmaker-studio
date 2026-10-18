package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What drawing a big file costs the FX thread, printed so a speed change can be measured headless.
 *
 * <p>A generated file shaped like Firestone's Screens.java (hundreds of constants, then methods of calls) is
 * rendered through the real {@code CodeEditorService} into a real {@link EditorCanvas} under real
 * {@link CenterTabs}, styled by {@code blocks.css}, then switched away from by its tab and back from Project
 * Files. Each render prints:
 * <ul>
 *   <li>nodes under the canvas, in total and by the block that owns them;</li>
 *   <li>the time to build the tree, and the time of {@code handleBlocksUpdate}'s forced CSS and layout;</li>
 *   <li>the CSS and layout time of each of the next pulses, and how many of them restyle a large part of the
 *   tree again (a pulse costing more than a tenth of the forced pass).</li>
 * </ul>
 *
 * <p>Only structure is asserted: the node count of the first render stays under {@link #NODE_CEILING}, so a
 * change cannot quietly add nodes back. Times depend on the machine and the shared toolkit, and never fail a
 * build. It measures the {@code ROADMAP.md} item "Render is the FX thread's cost now".
 */
class RenderBenchmarkTest extends FxHeadlessTest {

    /** Constants in the generated file, about Screens.java's count. */
    private static final int CONSTANTS = 300;
    /** Methods after the constants, each a handful of call blocks. */
    private static final int METHODS = 20;
    /** Nodes under the canvas after the first render, measured 2026-10-18 (16806) plus a margin; lower it as phases cut nodes. */
    private static final int NODE_CEILING = 18_000;
    /** Pulses measured after each render. */
    private static final int PULSES = 4;

    private StackPane root;
    private Scene scene;
    /** {@code [start, duration]} in nanoseconds of every pulse's CSS and layout, as the scene ran them. */
    private final List<long[]> pulses = new CopyOnWriteArrayList<>();
    private long pulseStart;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        scene = new Scene(root, 1000, 700);
        ThemedWindows.addStylesheet(scene);
        ThemedWindows.applyThemeClass(root);
        // A scene runs its pre-layout listeners, then the CSS pass, then the layout pass, then its post-layout
        // listeners: the gap between the two is what the pulse spent styling and laying out.
        scene.addPreLayoutPulseListener(() -> pulseStart = System.nanoTime());
        scene.addPostLayoutPulseListener(() -> pulses.add(new long[]{pulseStart, System.nanoTime() - pulseStart}));
        stage.setScene(scene);
        stage.show();
    }

    static String bigSource() {
        StringBuilder sb = new StringBuilder("package com.mybot;\npublic class Big {\n");
        for (int i = 0; i < CONSTANTS; i++) {
            switch (i % 3) {
                case 0 -> sb.append("    public static final int C").append(i).append(" = ").append(i).append(";\n");
                case 1 -> sb.append("    public static final String C").append(i).append(" = \"name ").append(i)
                        .append("\";\n");
                default -> sb.append("    public static final double C").append(i).append(" = ").append(i)
                        .append(".5;\n");
            }
        }
        for (int m = 0; m < METHODS; m++) {
            sb.append("    public void step").append(m).append("() {\n")
                    .append("        int a = C").append(m * 3).append(" + 1;\n")
                    .append("        System.out.println(C").append(m * 3 + 1).append(" + a);\n")
                    .append("        int b = Math.max(a, C").append(m * 3 + 3).append(");\n")
                    .append("        if (b > 2) {\n")
                    .append("            System.out.println(\"big \" + b);\n")
                    .append("        }\n")
                    .append("    }\n");
        }
        return sb.append("}\n").toString();
    }

    /** One render as the canvas saw it. */
    private record Render(String label, long buildNanos, long forcedNanos, long endedAt) {}

    /** Runs {@code work} on the FX thread, then waits until {@code count} more pulses have run on the scene. */
    private void onFx(Runnable work, int count) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the FX thread should have run the work");
        int seen = pulses.size();
        for (int i = 0; i < count; i++) {
            int wanted = seen + i + 1;
            Platform.runLater(Platform::requestNextPulse);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (pulses.size() < wanted) {
                assertTrue(System.nanoTime() < deadline, "a pulse should have run on the scene");
                Thread.sleep(5);
            }
        }
    }

    @Test
    void a_big_file_renders_under_the_node_ceiling_and_prints_what_it_cost() throws Exception {
        EditorFixture fixture = new EditorFixture(bigSource());
        CodeEditorService context = fixture.context();
        Path big = fixture.state.getActiveFile().getPath();
        ProjectFile other = new ProjectFile(Paths.get("Other.java").toAbsolutePath(),
                "package com.mybot;\npublic class Other {\n    public void go() {\n        int c = 3;\n    }\n}\n");
        fixture.state.addFile(other);

        List<Render> renders = new ArrayList<>();
        String[] label = {"first open"};
        EditorCanvas[] made = new EditorCanvas[1];
        CenterTabs[] tabs = new CenterTabs[1];
        onFx(() -> {
            made[0] = new EditorCanvas(context, fixture.bus(), false, "Big", () -> {}, List::of, () -> {},
                    List::of, () -> {}, List::of);
            // Wired as UIManager wires them, so a switch selects a tab the way a click on one does.
            tabs[0] = new CenterTabs(made[0].node());
            tabs[0].setEditor(context::switchToFile, () -> fixture.state.getActiveFile().getPath());
            root.getChildren().setAll(tabs[0].node());
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, event -> {
                long start = System.nanoTime();
                if (event.rootBlock() != null) event.rootBlock().getUINode(context);
                long built = System.nanoTime();
                made[0].handleBlocksUpdate(event);
                long end = System.nanoTime();
                renders.add(new Render(label[0], built - start, end - built, end));
            }, false);
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, e -> tabs[0].follow(), false);
        }, 2);

        onFx(fixture::rerender, PULSES);
        int firstNodes = report(renders, 1, made[0], fixture);

        // Away by a click on a tab, which switches the editor; back from Project Files, which switches the editor
        // first and leaves the tab to follow.
        label[0] = "switch away (tab)";
        onFx(() -> tabs[0].openJava(other.getPath()), PULSES);
        report(renders, 2, made[0], fixture);

        label[0] = "switch back (Project Files)";
        onFx(() -> context.switchToFile(big), PULSES);
        report(renders, 3, made[0], fixture);

        assertTrue(firstNodes < NODE_CEILING,
                "the first render drew " + firstNodes + " nodes, over the ceiling of " + NODE_CEILING
                        + ": a change added nodes back");
    }

    /** Prints the last render's numbers and returns its node count; the step must have made the {@code nth}. */
    private int report(List<Render> renders, int nth, EditorCanvas canvas, EditorFixture fixture) {
        assertEquals(nth, renders.size(), "each step should publish exactly one render");
        Render render = renders.getLast();
        Map<String, Integer> byBlock = new TreeMap<>();
        int total = countNodes(canvas.node(), owners(fixture), "(canvas)", byBlock);
        List<long[]> after = pulses.stream().filter(p -> p[0] >= render.endedAt()).limit(PULSES).toList();
        long heavy = after.stream().filter(p -> p[1] * 10 > render.forcedNanos()).count();
        StringBuilder out = new StringBuilder("\n[render-benchmark] ").append(render.label()).append('\n')
                .append(String.format("  nodes %d, build %.1f ms, forced css+layout %.1f ms%n", total,
                        ms(render.buildNanos()), ms(render.forcedNanos())))
                .append("  next pulses css+layout ms:");
        after.forEach(p -> out.append(String.format(" %.1f", ms(p[1]))));
        out.append(String.format("  (%d restyle a large part again)%n", heavy)).append("  nodes by block:");
        byBlock.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> out.append(' ').append(e.getKey()).append('=').append(e.getValue()));
        System.out.println(out);
        return total;
    }

    /** Each built block node, by identity, to its block's type name. */
    private static Map<Node, String> owners(EditorFixture fixture) {
        Map<Node, String> owners = new IdentityHashMap<>();
        for (CodeBlock block : fixture.state.getNodeToBlockMap().values()) {
            Node node = block.getUINode();
            if (node != null) owners.put(node, block.getClass().getSimpleName());
        }
        return owners;
    }

    /** Counts {@code node} and everything under it, each charged to the nearest block node above it. */
    private static int countNodes(Node node, Map<Node, String> owners, String owner, Map<String, Integer> byBlock) {
        String charged = owners.getOrDefault(node, owner);
        byBlock.merge(charged, 1, Integer::sum);
        int count = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) count += countNodes(child, owners, charged, byBlock);
        }
        return count;
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
