package com.botmaker.studio.ui.render;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockStyle;
import com.botmaker.studio.ui.render.theme.BlockStylePreference;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every word on a block is readable — measured on the pixels the stylesheet actually produces, in all four
 * themes and both block styles.
 *
 * <p>{@link BlockPaletteContrastTest} measures the palette's declared pairs, and cannot see the rest: a field's
 * well is {@code derive()}d from the block's fill in the dark themes, a reporter is a translucent wash over
 * whatever it sits on, and Modena ladders a control's text against a surface this stylesheet re-points. None of
 * those is a hex literal to read. So this draws a program, finds every {@link Text} inside a painted block, and
 * measures its fill against the colour behind it — composited up the parent chain, translucent layers and all.
 *
 * <p>Full-strength text must clear WCAG AA (4.5:1). Text drawn deliberately quieter (a caption at 60–80%, the
 * ghosted {@code +}/{@code x} glyphs) is a secondary cue and is held to 3:1 at its own opacity, or skipped
 * where it is an icon that shows at full strength under the pointer.
 */
class BlockStyleContrastTest extends FxHeadlessTest {

    private static final double AA = 4.5;
    private static final double SECONDARY = 3.0;

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        Scene scene = new Scene(root, 900, 780);
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
    void everyWordOnABlockIsReadableInEveryThemeAndStyle() throws Exception {
        EditorFixture fixture = new EditorFixture(com.botmaker.studio.ui.app.BlockGalleryTest.PROGRAM);
        // The service is what renders on UIRefreshRequested, and it is built lazily — build it first.
        fixture.context();
        onFx(() -> fixture.subscribeBlocksUpdated(block -> {
            Parent canvas = new StackPane(block.getUINode(fixture.context()));
            canvas.getStyleClass().add("blocks-canvas");
            root.getChildren().setAll(canvas);
        }));
        onFx(fixture::rerender);

        BlockTheme.ThemeType themeBefore = BlockTheme.getCurrentThemeType();
        BlockStyle styleBefore = BlockStylePreference.style();
        List<String> failures = new ArrayList<>();
        int[] measured = {0};
        try {
            for (BlockStyle style : BlockStyle.values()) {
                for (BlockTheme.ThemeType theme : BlockTheme.ThemeType.values()) {
                    onFx(() -> {
                        BlockTheme.setTheme(theme);
                        ThemedWindows.applyThemeClass(root);
                        Node canvas = root.getChildren().getFirst();
                        canvas.getStyleClass().removeAll(BlockStyle.styleClasses());
                        canvas.getStyleClass().add(style.styleClass());
                        root.applyCss();
                        root.layout();
                    });
                    onFx(() -> measure(root, style + "/" + theme, failures, measured));
                }
            }

            // Locked: every block read-only, the look a generated file has. Faded on purpose, so held to the
            // secondary floor — but held to it, since a locked file is the one whose code the user can only read.
            onFx(() -> {
                var locked = fixture.reparse(com.botmaker.studio.ui.app.BlockGalleryTest.PROGRAM,
                        com.botmaker.studio.parser.BlockReuse.NONE);
                fixture.state.getNodeToBlockMap().values().forEach(block -> block.setReadOnly(true));
                locked.root().setReadOnly(true);
                Parent canvas = new StackPane(locked.root().getUINode(fixture.context()));
                canvas.getStyleClass().addAll("blocks-canvas", BlockStyle.DEFAULT.styleClass());
                root.getChildren().setAll(canvas);
            });
            for (BlockTheme.ThemeType theme : BlockTheme.ThemeType.values()) {
                onFx(() -> {
                    BlockTheme.setTheme(theme);
                    ThemedWindows.applyThemeClass(root);
                    root.applyCss();
                    root.layout();
                });
                onFx(() -> measure(root, "LOCKED/" + theme, failures, measured));
            }
        } finally {
            onFx(() -> {
                BlockTheme.setTheme(themeBefore);
                BlockStylePreference.set(styleBefore);
            });
        }

        assertTrue(measured[0] > 200, "the program should put a few hundred words on blocks, measured " + measured[0]);
        assertTrue(failures.isEmpty(), failures.size() + " unreadable words:\n" + String.join("\n", failures));
    }

    private static void measure(Node node, String where, List<String> failures, int[] measured) {
        if (node instanceof Text text && !text.getText().isBlank() && shown(text) && insideABlock(text)) {
            if (isIcon(text)) return;
            Color ink = solid(text.getFill());
            if (ink == null) return;
            Color seen = rendered(text, premultiplied(ink, text.getOpacity()));
            Color paper = rendered(text, TRANSPARENT);
            double ratio = contrast(seen, paper);
            double floor = effectiveOpacity(text) >= 0.95 ? AA : SECONDARY;
            measured[0]++;
            if (ratio < floor) {
                failures.add(String.format("%s: '%s' %s on %s scores %.2f (needs %.1f) in %s",
                        where, text.getText(), hex(seen), hex(paper), ratio, floor, path(text)));
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) measure(child, where, failures, measured);
        }
    }

    /** Drawn right now: a separator's insert "+" is in the tree all the time and visible only under the pointer. */
    private static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    /** Only text on a block is this stylesheet's to answer for; the class card and its buttons are chrome. */
    private static boolean insideABlock(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("block")) return true;
        }
        return false;
    }

    /** A ghosted glyph button: a secondary cue by design, full strength under the pointer. */
    private static boolean isIcon(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("icon-button")) return true;
            if (n.getStyleClass().contains("block")) return false;
        }
        return false;
    }

    private static double effectiveOpacity(Node node) {
        double opacity = 1;
        for (Node n = node; n != null; n = n.getParent()) opacity *= n.getOpacity();
        return opacity;
    }

    /** Premultiplied RGBA: what a translucent layer or a faded group is, before it lands on anything. */
    private record Rgba(double r, double g, double b, double a) {}

    private static final Rgba TRANSPARENT = new Rgba(0, 0, 0, 0);

    private static Rgba premultiplied(Color c, double opacity) {
        double a = c.getOpacity() * opacity;
        return new Rgba(c.getRed() * a, c.getGreen() * a, c.getBlue() * a, a);
    }

    private static Rgba over(Rgba top, Rgba below) {
        double k = 1 - top.a();
        return new Rgba(top.r() + below.r() * k, top.g() + below.g() * k, top.b() + below.b() * k,
                top.a() + below.a() * k);
    }

    /**
     * The colour the window shows at {@code text}'s spot, given what is drawn there at the text's own level
     * ({@code start}: its ink, or nothing to get the paper). Composited the way JavaFX renders: each ancestor
     * draws its background under its children's content, and an ancestor's opacity fades that whole group —
     * ink and background alike — against what is behind it. Translucent washes and faded groups are exactly
     * what a declared-token check cannot see.
     */
    private static Color rendered(Text text, Rgba start) {
        Rgba content = start;
        for (Node n = text.getParent(); n != null; n = n.getParent()) {
            Color fill = n instanceof Region region ? topFill(region.getBackground()) : null;
            if (fill != null) content = over(content, premultiplied(fill, 1));
            double alpha = n.getOpacity();
            if (alpha < 1) content = new Rgba(content.r() * alpha, content.g() * alpha, content.b() * alpha, content.a() * alpha);
        }
        Rgba seen = over(content, premultiplied(Color.WHITE, 1));
        return new Color(clamp(seen.r()), clamp(seen.g()), clamp(seen.b()), 1);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static Color topFill(Background background) {
        if (background == null || background.getFills().isEmpty()) return null;
        for (int i = background.getFills().size() - 1; i >= 0; i--) {
            BackgroundFill fill = background.getFills().get(i);
            Color c = solid(fill.getFill());
            if (c != null && c.getOpacity() > 0.01) return c;
        }
        return null;
    }

    /** A paint as one colour: a gradient (Modena's bevels) is read as its average stop. */
    private static Color solid(Paint paint) {
        if (paint instanceof Color color) return color;
        if (paint instanceof LinearGradient gradient && !gradient.getStops().isEmpty()) {
            double r = 0, g = 0, b = 0, a = 0;
            for (Stop stop : gradient.getStops()) {
                r += stop.getColor().getRed();
                g += stop.getColor().getGreen();
                b += stop.getColor().getBlue();
                a += stop.getColor().getOpacity();
            }
            int n = gradient.getStops().size();
            return new Color(r / n, g / n, b / n, a / n);
        }
        return null;
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private static String path(Node node) {
        StringBuilder out = new StringBuilder();
        for (Node n = node.getParent(); n != null && out.length() < 400; n = n.getParent()) {
            if (!n.getStyleClass().isEmpty()) out.append(' ').append(n.getStyleClass());
            if (n.getOpacity() < 1) out.append("@").append(n.getOpacity()).append(n.getPseudoClassStates());
        }
        return out.toString().trim();
    }
}
