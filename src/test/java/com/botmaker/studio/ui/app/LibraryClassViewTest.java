package com.botmaker.studio.ui.app;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.nav.LibrarySource;
import com.botmaker.studio.nav.LibrarySource.Origin;
import com.botmaker.studio.nav.LibrarySource.Target;
import com.botmaker.studio.nav.LibrarySource.View;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Go to Definition on a library draws the whole class as locked blocks, landed on the member: its own source
 * when there is one, an outline whose every function says it has no source otherwise, and a nested class on its
 * own, since the canvas has no row for a class inside a class.
 */
class LibraryClassViewTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package com.acme;

            public class Tool {
                public static int run() {
                    int n = 1;
                    return n;
                }

                public static int run(int times) {
                    return times;
                }
            }
            """;

    private final EditorFixture fixture = new EditorFixture("package com.mybot;\npublic class Bot {}\n");

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        stage.setScene(new Scene(root, 800, 600));
        stage.show();
    }

    private BlockConverter converter() {
        return new BlockConverter(null, fixture.state);
    }

    private LibraryClassView.Drawn draw(View view) {
        return LibraryClassView.draw(view, converter(), new BlockDragAndDropManager(fixture.bus()));
    }

    @Test
    void aClassWithSourcesIsDrawnWholeLockedAndLandsOnTheMember() {
        View view = new View("Tool", SOURCE, 9, Origin.SOURCES, Path.of("tool-1.0-sources.jar"));
        LibraryClassView.Drawn drawn = draw(view);

        assertNotNull(drawn.root(), "the class should be drawn");
        assertTrue(drawn.blocks().values().stream().allMatch(CodeBlock::isReadOnly), "every block is locked");
        assertInstanceOf(MethodDeclarationBlock.class, drawn.target());
        MethodDeclaration landed = (MethodDeclaration) drawn.target().getAstNode();
        assertEquals(1, landed.parameters().size(), "line 9 declares run(int), not run()");
        assertTrue(drawn.problems().isEmpty(), drawn.problems().toString());
    }

    @Test
    void theOpenFileIsNotTouched() {
        var before = fixture.state.getNodeToBlockMap();
        draw(new View("Tool", SOURCE, 4, Origin.SOURCES, null));
        assertEquals(before, fixture.state.getNodeToBlockMap(), "a library's blocks never join the open file's");
    }

    @Test
    void anOutlineSaysEachFunctionHasNoSource() {
        View view = LibrarySource.locate(new Target("java.util.List", "java.util", "size", 0), List.of(), null,
                jar -> Optional.empty()).orElseThrow();
        assumeTrue(view.origin() == Origin.OUTLINE, "no src.zip was given, so the JDK is outlined");

        LibraryClassView.Drawn drawn = draw(view);

        List<MethodDeclarationBlock> functions = drawn.blocks().values().stream()
                .filter(MethodDeclarationBlock.class::isInstance).map(MethodDeclarationBlock.class::cast).toList();
        assertTrue(functions.size() > 10, "List's outline lists its functions (" + functions.size() + ")");
        List<String> unsaid = functions.stream().filter(f -> !LibraryClassView.NO_SOURCE.equals(f.missingBody()))
                .map(f -> f.getAstNode().toString().lines().findFirst().orElse("")).toList();
        assertEquals(List.of(), unsaid, "an outlined function says it has no source where its body would be");
        assertEquals("size", ((MethodDeclaration) drawn.target().getAstNode()).getName().getIdentifier());
    }

    @Test
    void aMemberOfANestedClassDrawsThatClass() {
        View view = LibrarySource.locate(new Target("java.util.Map$Entry", "java.util", "getKey", 0), List.of(),
                null, jar -> Optional.empty()).orElseThrow();

        LibraryClassView.Drawn drawn = draw(view);

        assertEquals("Entry", ((TypeDeclaration) drawn.root().getAstNode()).getName().getIdentifier());
        assertEquals("getKey", ((MethodDeclaration) drawn.target().getAstNode()).getName().getIdentifier());
    }

    @Test
    void noLineLandsNowhere() {
        LibraryClassView.Drawn drawn = draw(new View("Tool", SOURCE, 0, Origin.SOURCES, null));
        assertNull(drawn.target());
        assertNotNull(drawn.root());
    }

    @Test
    void theBannerSaysWhereTheClassCameFrom() {
        assertEquals("Library code, read-only. From tool-1.0-sources.jar.", LibraryClassView.banner(
                new View("Tool", SOURCE, 0, Origin.SOURCES, Path.of("/m2/tool-1.0-sources.jar")), List.of()));
        assertTrue(LibraryClassView.banner(new View("List", "", 0, Origin.OUTLINE, null), List.of())
                .contains("No sources were found for the Java runtime"));
    }

    /**
     * {@code java.lang.String} is the class a Ctrl-click most often lands in, and one of the longest: drawing it
     * whole is the cost to watch. Measured, printed, and held under a bound generous enough for a busy CI.
     *
     * <p>Measured 2026-09-27: 14 s with every body built, which is why a library's functions open folded and
     * build their bodies on first open. Then about 1.5 s warm — 0.2 s parsing, 0.6 s building ~130 headers,
     * 0.4 s CSS, 0.1 s layout.
     */
    @Test
    void theJdksStringDrawsInReasonableTime() throws Exception {
        Path srcZip = Path.of(System.getProperty("java.home"), "lib", "src.zip");
        assumeTrue(Files.isRegularFile(srcZip), "this JDK ships no src.zip");
        View view = LibrarySource.locate(new Target("java.lang.String", "java.lang", "isBlank", 0), List.of(),
                Path.of(System.getProperty("java.home")), jar -> Optional.empty()).orElseThrow();
        assertEquals(Origin.SOURCES, view.origin());

        long parsed = System.nanoTime();
        draw(view);
        System.out.println("LibraryClassViewTest: java.lang.String parsed into blocks in "
                + (System.nanoTime() - parsed) / 1_000_000 + " ms");
        // Twice: the first build in a fresh JVM also pays for loading the stylesheet and the fonts, which the
        // running app has long since paid; the second is what a user waits for.
        Node[] built = new Node[1];
        long millis = 0;
        for (int run = 1; run <= 2; run++) {
            long start = System.nanoTime();
            onFx(() -> {
                built[0] = LibraryClassView.build(view, converter(), fixture.context());
                root.getChildren().setAll(built[0]);
                root.applyCss();
                root.layout();
            });
            millis = (System.nanoTime() - start) / 1_000_000;
            System.out.println("LibraryClassViewTest: java.lang.String drawn in " + millis + " ms (run " + run + ")");
        }
        assertNotNull(built[0]);
        assertTrue(millis < 30_000, "drawing String took " + millis + " ms");
    }

    private static void onFx(Runnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failed = new Throwable[1];
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failed[0] = t;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the FX thread should have run the work");
        if (failed[0] != null) throw new AssertionError(failed[0]);
    }
}
