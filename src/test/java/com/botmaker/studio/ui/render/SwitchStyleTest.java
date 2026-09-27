package com.botmaker.studio.ui.render;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockStyle;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The on/off switch (the toolkit's {@code value-switch}) is filled red when off and green when on, whatever
 * block it sits in (feedback 3, 2026-09-27): it coloured only its word and border, so its fill was the block's
 * and a purple block's switch read purple. Measured on the rendered control, in every theme and block style.
 */
class SwitchStyleTest extends FxHeadlessTest {

    private static final List<String> CATEGORIES = List.of("category-flow", "category-variables", "category-game");

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();
        Scene scene = new Scene(root, 400, 200);
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
    }

    /** The topmost opaque fill a region paints — what the eye sees behind its text. */
    private static Color fill(ToggleButton button) {
        List<BackgroundFill> fills = button.getBackground().getFills();
        javafx.scene.paint.Paint top = fills.getLast().getFill();
        if (top instanceof javafx.scene.paint.LinearGradient gradient) return gradient.getStops().getFirst().getColor();
        return (Color) top;
    }

    private static double luminance(Color c) {
        double[] rgb = {c.getRed(), c.getGreen(), c.getBlue()};
        for (int i = 0; i < 3; i++) {
            rgb[i] = rgb[i] <= 0.03928 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    void offIsRedAndOnIsGreenInsideEveryBlock() throws Exception {
        BlockTheme.ThemeType themeBefore = BlockTheme.getCurrentThemeType();
        List<String> failures = new ArrayList<>();
        try {
            for (BlockStyle style : BlockStyle.values()) {
                for (BlockTheme.ThemeType theme : BlockTheme.ThemeType.values()) {
                    List<Color> offs = new ArrayList<>();
                    List<Color> ons = new ArrayList<>();
                    for (String category : CATEGORIES) {
                        ToggleButton off = new ToggleButton("Off");
                        ToggleButton on = new ToggleButton("On");
                        on.setSelected(true);
                        off.getStyleClass().add("value-switch");
                        on.getStyleClass().add("value-switch");
                        onFx(() -> {
                            BlockTheme.setTheme(theme);
                            ThemedWindows.applyThemeClass(root);
                            HBox block = new HBox(4, off, on);
                            block.getStyleClass().addAll("block", "block-category", category);
                            StackPane canvas = new StackPane(block);
                            canvas.getStyleClass().addAll("blocks-canvas", style.styleClass());
                            root.getChildren().setAll(canvas);
                            root.applyCss();
                            root.layout();
                        });
                        String where = style + "/" + theme + "/" + category;
                        onFx(() -> {
                          try {
                            Color offFill = fill(off);
                            Color onFill = fill(on);
                            offs.add(offFill);
                            ons.add(onFill);
                            if (!(offFill.getRed() > offFill.getGreen() + 0.3)) failures.add(where + " off is not red: " + offFill);
                            if (!(onFill.getGreen() > onFill.getRed() + 0.15)) failures.add(where + " on is not green: " + onFill);
                            for (ToggleButton b : List.of(off, on)) {
                                Text word = (Text) b.lookup(".text");
                                double ratio = contrast((Color) word.getFill(), fill(b));
                                if (ratio < 4.5) failures.add(where + " " + b.getText() + " text " + ratio);
                            }
                          } catch (RuntimeException e) {
                            failures.add(where + " could not be measured: " + e);
                          }
                        });
                    }
                    // The same fill in every block: the switch's colour is its own, never the block's.
                    for (List<Color> fills : List.of(offs, ons)) {
                        if (fills.stream().distinct().count() != 1) failures.add(style + "/" + theme + " varies: " + fills);
                    }
                }
            }
        } finally {
            onFx(() -> BlockTheme.setTheme(themeBefore));
        }
        assertEquals(List.of(), failures);
    }
}
