package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockStyle;
import com.botmaker.studio.ui.render.theme.BlockStylePreference;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.CanvasZoom;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block gallery: one program drawn in every theme, and at three zoom levels, written out as PNGs.
 *
 * <p><b>Not a test</b>, and skipped unless {@code BM_BLOCK_GALLERY} names an output directory. It exists
 * because the block look is CSS, and CSS has no failing assertion: the only way to review a change to
 * {@code blocks.css} is to look at it in all four themes. The pictures in
 * {@code docs/refactor/37-block-styling.md} are this tool's output, so regenerating them is one command:
 *
 * <pre>BM_BLOCK_GALLERY=/tmp/gallery mvn test -Dtest=BlockGalleryTest</pre>
 *
 * <p>An environment variable rather than a {@code -D}: Surefire forks the test JVM, and an environment
 * variable reaches the fork without a line of pom configuration.
 */
@EnabledIfEnvironmentVariable(named = "BM_BLOCK_GALLERY", matches = ".+")
public class BlockGalleryTest extends FxHeadlessTest {

    /**
     * Every shape the canvas draws: stack, C-block, else-chain, reporter, boolean, list, header. Public because
     * the tests that hold the style to account (contrast, zoom fit) measure the same program the gallery shows.
     */
    public static final String PROGRAM = """
            package com.mybot;

            import java.util.List;

            public class Gallery {

                // Collects ore until the bag is full.
                public static int collect(int limit) {
                    int count = 0;
                    boolean full = false;
                    List<String> names = List.of("iron", "gold");
                    while (count < limit && !full) {
                        System.out.println("Mining ore number " + count);
                        count = count + 1;
                        if (count > 10) {
                            full = true;
                        } else if (count == 5) {
                            System.out.println("halfway");
                        } else {
                            continue;
                        }
                    }
                    for (String name : names) {
                        System.out.println(name);
                    }
                    return count;
                }
            }
            """;

    /**
     * Every statement block added in Phase 4 of the block plan, plus the source block the rest fall back to.
     * A second program rather than more lines in {@link #PROGRAM}, whose block counts other tests pin.
     */
    public static final String STATEMENTS = """
            package com.mybot;

            public class Statements {

                private final Object lock = new Object();

                public void guard(int rounds) {
                    outer:
                    for (int i = 0; i < rounds; i++) {
                        try {
                            synchronized (lock) {
                                assert i >= 0 : "negative round";
                            }
                        } catch (IllegalStateException | IllegalArgumentException e) {
                            break outer;
                        } finally {
                            System.out.println("round done");
                        }
                    }
                    int low = 1, high = 9;
                    new StringBuilder("unused");
                    throw new IllegalStateException("stopped");
                }
            }
            """;

    /** Every value block added in Phase 5 of the block plan, one per line, plus the source block they fall back to. */
    public static final String EXPRESSIONS = """
            package com.mybot;

            public class Values extends Base {

                private Object found = "ore";
                private int[] counts = {3, 1};

                public void look(int round) {
                    int bonus = round > 3 ? round * 2 : 0;
                    int whole = (int) 2.5 + counts[round - 1] + round++;
                    boolean isText = found instanceof String name && !name.isEmpty();
                    Runnable tick = () -> System.out.println(round);
                    java.util.function.IntUnaryOperator twice = x -> {
                        return x * 2;
                    };
                    int[] slots = new int[round];
                    char mark = 'x';
                    int mask = 0xFF;
                    String note = \"""
                        two
                        lines\""";
                    Class<?> kind = String.class;
                    Object self = this;
                    int parentSize = super.size - -round;
                    int days = switch (round) {
                        case 1, 2 -> 28;
                        default -> 31;
                    };
                    Runnable later = new Runnable() { public void run() { } };
                }
            }

            class Base {
                protected int size;
            }
            """;

    private StackPane root;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        // Inside Monocle's headless screen (1280×800): a stage past its edge paints nothing there.
        scene = new Scene(root, 760, 780);
        ThemedWindows.addStylesheet(scene);
        stage.setScene(scene);
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
        for (int i = 0; i < 3; i++) {
            CountDownLatch drained = new CountDownLatch(1);
            Platform.runLater(drained::countDown);
            assertTrue(drained.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void drawTheGallery() throws Exception {
        Path out = Path.of(System.getenv("BM_BLOCK_GALLERY"));
        Files.createDirectories(out);

        EditorFixture fixture = new EditorFixture(PROGRAM);
        EditorCanvas[] made = new EditorCanvas[1];
        onFx(() -> {
            made[0] = new EditorCanvas(fixture.context(), fixture.bus(), false, "Gallery", () -> {}, List::of, () -> {});
            root.getChildren().setAll(made[0].node());
            fixture.bus().subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, made[0]::handleBlocksUpdate, false);
        });
        onFx(fixture::rerender);

        // Both are user preferences, saved on every set: put back what the developer had, not the defaults.
        BlockTheme.ThemeType themeBefore = BlockTheme.getCurrentThemeType();
        double zoomBefore = CanvasZoom.factor();
        BlockStyle styleBefore = BlockStylePreference.style();
        try {
            onFx(() -> CanvasZoom.set(CanvasZoom.DEFAULT));
            for (BlockStyle style : BlockStyle.values()) {
                onFx(() -> BlockStylePreference.set(style));
                for (BlockTheme.ThemeType theme : BlockTheme.ThemeType.values()) {
                    onFx(() -> {
                        BlockTheme.setTheme(theme);
                        ThemedWindows.applyThemeClass(root);
                    });
                    snapProgram(out.resolve(style.id() + "-" + theme.name().toLowerCase().replace('_', '-') + ".png"));
                }
            }
            onFx(() -> {
                BlockStylePreference.set(BlockStyle.DEFAULT);
                BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT);
                ThemedWindows.applyThemeClass(root);
            });

            // The three states a block shows: the debugger's line, a compile error, a breakpoint.
            onFx(() -> {
                var blocks = fixture.state.getNodeToBlockMap();
                blocks.forEach((node, block) -> {
                    if (node instanceof org.eclipse.jdt.core.dom.WhileStatement) block.highlight();
                    if (node instanceof org.eclipse.jdt.core.dom.ReturnStatement) block.setError("count is never read");
                    if (node instanceof org.eclipse.jdt.core.dom.EnhancedForStatement) block.setBreakpoint(true);
                });
            });
            snapProgram(out.resolve("states.png"));
            onFx(() -> fixture.state.getNodeToBlockMap().values().forEach(block -> {
                block.unhighlight();
                block.clearError();
                block.setBreakpoint(false);
            }));
            for (double zoom : new double[]{0.7, 1.5}) {
                onFx(() -> CanvasZoom.set(zoom));
                snap(out.resolve("zoom-" + Math.round(zoom * 100) + ".png"));
            }
            onFx(() -> CanvasZoom.set(CanvasZoom.DEFAULT));

            // The same program locked — every block read-only, as a file a plugin generated is — and then under
            // Reader mode, which puts the colour back.
            for (boolean reader : new boolean[]{false, true}) {
                onFx(() -> {
                    // A fresh tree (no reuse, so no node is drawn yet), every block of it locked by hand: the
                    // fixture's lock resolver answers per file, and this file is an editable one.
                    var locked = fixture.reparse(PROGRAM, com.botmaker.studio.parser.BlockReuse.NONE);
                    fixture.state.getNodeToBlockMap().values().forEach(block -> block.setReadOnly(true));
                    locked.root().setReadOnly(true);
                    VBox canvas = new VBox(locked.root().getUINode(fixture.context()));
                    canvas.getStyleClass().addAll("blocks-canvas", BlockStyle.DEFAULT.styleClass());
                    if (reader) canvas.getStyleClass().add("reader-mode");
                    canvas.setPadding(new javafx.geometry.Insets(20));
                    ScrollPane pane = new ScrollPane(canvas);
                    pane.setFitToWidth(true);
                    pane.getStyleClass().add("code-scroll-pane");
                    root.getChildren().setAll(pane);
                });
                snapProgram(out.resolve(reader ? "reader-mode.png" : "locked.png"));
            }

            // The statement blocks (try, the counting loop, throw, the source block) and the value blocks (a
            // choice, a cast, a lambda, a switch value…) — in each style, light and dark.
            for (Map.Entry<String, String> program : Map.of("statements", STATEMENTS, "expressions", EXPRESSIONS).entrySet()) {
            for (BlockStyle style : BlockStyle.values()) {
                for (BlockTheme.ThemeType theme : List.of(BlockTheme.ThemeType.DEFAULT, BlockTheme.ThemeType.DARK)) {
                    onFx(() -> {
                        BlockTheme.setTheme(theme);
                        ThemedWindows.applyThemeClass(root);
                        var drawn = fixture.reparse(program.getValue(), com.botmaker.studio.parser.BlockReuse.NONE);
                        VBox canvas = new VBox(drawn.root().getUINode(fixture.context()));
                        canvas.getStyleClass().addAll("blocks-canvas", style.styleClass());
                        canvas.setPadding(new javafx.geometry.Insets(20));
                        ScrollPane pane = new ScrollPane(canvas);
                        pane.setFitToWidth(true);
                        pane.getStyleClass().add("code-scroll-pane");
                        root.getChildren().setAll(pane);
                    });
                    snapProgram(out.resolve(program.getKey() + "-" + style.id() + "-"
                            + theme.name().toLowerCase().replace('_', '-') + ".png"));
                }
            }
            }
        } finally {
            onFx(() -> {
                CanvasZoom.set(zoomBefore);
                BlockTheme.setTheme(themeBefore);
                BlockStylePreference.set(styleBefore);
            });
        }
    }

    /**
     * The whole program, not the viewport: the canvas node is drawn at its full height whatever the scroll pane
     * shows. The fill behind it is the window's own background, read off the scroll pane, so a dark theme is
     * not pictured on white.
     */
    private void snapProgram(Path file) throws Exception {
        onFx(() -> {
            root.applyCss();
            root.layout();
        });
        onFx(() -> {
            Node canvas = root.lookup(".blocks-canvas");
            Region viewport = (Region) root.lookup(".code-scroll-pane").lookup(".viewport");
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(viewport.getBackground() == null || viewport.getBackground().getFills().isEmpty()
                    ? Color.WHITE : viewport.getBackground().getFills().getLast().getFill());
            try {
                write(canvas.snapshot(params, null), file.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void snap(Path file) throws Exception {
        onFx(() -> {
            root.applyCss();
            root.layout();
        });
        onFx(() -> {
            try {
                write(scene.snapshot(null), file.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** {@code SwingFXUtils} lives in {@code javafx.swing}, which Studio does not depend on; this is its one loop. */
    private static void write(WritableImage image, File file) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) buffered.setRGB(x, y, pixels.getArgb(x, y));
        }
        ImageIO.write(buffered, "png", file);
    }
}
