package com.botmaker.studio.ui.app.reports;

import com.botmaker.runlog.ReportTarget;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.control.ComboBox;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Reports tab, smoke: a week of fake problems drawn, a bar listing its problems. With {@code BM_REPORTS_SHOTS}
 * set to a folder, it also writes what it drew there as PNGs, light and dark.
 */
class ReportsPanelTest extends FxHeadlessTest {

    private ReportsPanel panel;
    private StackPane root;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        panel = new ReportsPanel(Path.of("/nowhere"), (file, line) -> { },
                Optional.of(new ReportTarget("LiQiyeDev", "firestone-bot")),
                ReportChartsTest::week, t -> ReportChartsTest.week());
        root = new StackPane(panel.node());
        scene = new Scene(root, 1200, 420);
        ThemedWindows.addStylesheet(scene);
        stage.setScene(scene);
        stage.show();
    }

    private void onFx(Runnable work) {
        Platform.runLater(work);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private StackedBarChart<?, ?> chart() throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> root.lookup(".chart") != null);
        return (StackedBarChart<?, ?>) root.lookup(".chart");
    }

    @SuppressWarnings("unchecked")
    private <T> ComboBox<T> combo(int index) {
        return (ComboBox<T>) root.lookupAll(".combo-box").stream().toList().get(index);
    }

    @Test
    void aWeekIsDrawnAndABarListsItsProblems() throws Exception {
        StackedBarChart<?, ?> bars = chart();
        assertEquals(List.of("Warning", "Error"), bars.getData().stream().map(s -> s.getName()).toList());

        onFx(() -> panel.pick("com.firestone.Map:77"));
        assertEquals(1, panel.rowsShown().size());
        assertEquals("Error · Map:77 · Flow.go ×4 #3 #19", ReportsPanel.rowText(panel.rowsShown().getFirst()));

        Node bar = bars.getData().getFirst().getData().getFirst().getNode();
        assertNotNull(bar);
        clickOn(bar);
        assertEquals("com.firestone.Daily:42", panel.pickedValue());
        assertFalse(panel.rowsShown().isEmpty());

        shots();
    }

    /** Pictures of both views, light and dark, when asked for. */
    private void shots() throws Exception {
        String to = System.getenv("BM_REPORTS_SHOTS");
        if (to == null || to.isBlank()) return;
        Path out = Files.createDirectories(Path.of(to));
        for (BlockTheme.ThemeType theme : List.of(BlockTheme.ThemeType.DEFAULT, BlockTheme.ThemeType.DARK)) {
            onFx(() -> {
                BlockTheme.setTheme(theme);
                ThemedWindows.applyThemeClass(root);
            });
            onFx(() -> combo(1).getSelectionModel().select(0));
            onFx(() -> combo(2).getSelectionModel().select(ReportsPanel.View.TOTALS));
            onFx(() -> panel.pick("com.firestone.Daily:42"));
            snap(out.resolve("totals-" + theme.name().toLowerCase() + ".png"));
            onFx(() -> combo(1).getSelectionModel().select(3));
            onFx(() -> combo(2).getSelectionModel().select(ReportsPanel.View.BY_DAY));
            onFx(() -> panel.pick("Map"));
            snap(out.resolve("by-day-" + theme.name().toLowerCase() + ".png"));
        }
        onFx(() -> BlockTheme.setTheme(BlockTheme.ThemeType.DEFAULT));
    }

    private void snap(Path file) {
        onFx(() -> {
            root.applyCss();
            root.layout();
        });
        onFx(() -> {
            try {
                write(scene.snapshot(null), file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void write(WritableImage image, Path file) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) buffered.setRGB(x, y, pixels.getArgb(x, y));
        }
        ImageIO.write(buffered, "png", file.toFile());
    }
}
