package com.botmaker.studio.ui.app.reports;

import com.botmaker.runlog.ReportTarget;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                ReportChartsTest::week, t -> ReportChartsTest.week(),
                () -> List.of(ReportChartsTest.PER_SCREEN, ReportChartsTest.MOVES));
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

    private static final int VIEW = 1;
    private static final int GROUP_BY = 2;
    private static final int KIND = 3;

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

    @Test
    void everyKindDrawsAndItsValuesPick() throws Exception {
        chart();
        onFx(() -> combo(GROUP_BY).getSelectionModel().select(0)); // Bot line
        String daily = "com.firestone.Daily:42";

        kind(ReportsPanel.Kind.LINES);
        LineChart<?, ?> lines = (LineChart<?, ?>) root.lookup(".chart");
        assertEquals(List.of("com.firestone.Daily:42", "com.firestone.Shop:118", "com.firestone.Map:77"),
                lines.getData().stream().map(s -> s.getName()).toList());
        clickOn(lines.getData().getFirst().getData().get(3).getNode());
        assertEquals(daily, panel.pickedValue());

        kind(ReportsPanel.Kind.HEATMAP);
        GridPane grid = (GridPane) root.lookup(".reports-heatmap");
        assertNotNull(grid);
        Node mapCell = grid.getChildren().stream()
                .filter(n -> GridPane.getRowIndex(n) == 3 && GridPane.getColumnIndex(n) == 6).findFirst().orElseThrow();
        assertEquals("4", ((Label) mapCell).getText());
        clickOn(mapCell);
        assertEquals("com.firestone.Map:77", panel.pickedValue());

        kind(ReportsPanel.Kind.DONUT);
        PieChart pie = (PieChart) root.lookup(".chart");
        assertEquals(3, pie.getData().size());
        // A big slice's box centre may lie on another slice: the click goes to the slice itself.
        Node slice = pie.getData().getFirst().getNode();
        onFx(() -> slice.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null)));
        assertEquals(daily, panel.pickedValue());

        kind(ReportsPanel.Kind.TABLE);
        TableView<?> table = (TableView<?>) root.lookup(".reports-table");
        assertEquals(3, table.getItems().size());
        onFx(() -> table.getSelectionModel().select(1));
        assertEquals("com.firestone.Shop:118", panel.pickedValue());
        assertFalse(panel.rowsShown().isEmpty());
    }

    @Test
    void aPluginsViewIsDrawnOrSaysWhatTheReportsLack() throws Exception {
        chart();
        ComboBox<ReportsPanel.View> views = combo(VIEW);
        assertEquals(List.of("Any dimension", "Problems per screen", "Failed moves"),
                views.getItems().stream().map(ReportsPanel.View::title).toList());

        onFx(() -> views.getSelectionModel().select(2)); // Failed moves, over a week that names no screen
        Label lacking = (Label) chartHolderChild();
        assertTrue(lacking.getText().contains("from and to"), lacking.getText());
        assertTrue(combo(KIND).isDisabled());

        onFx(() -> panel.show(ReportChartsTest.screens()));
        assertEquals("Failed moves", views.getValue().title(), "a reload keeps the view");
        GridPane grid = (GridPane) root.lookup(".reports-heatmap");
        assertNotNull(grid);
        Node titleToHome = grid.getChildren().stream()
                .filter(n -> GridPane.getRowIndex(n) == 1 && GridPane.getColumnIndex(n) == 1).findFirst().orElseThrow();
        assertEquals("2", ((Label) titleToHome).getText());
        clickOn(titleToHome);
        assertEquals("Title", panel.pickedValue());
        assertEquals(1, panel.rowsShown().size(), "the warning on Title is not this view's");

        onFx(() -> views.getSelectionModel().select(0));
        assertFalse(combo(KIND).isDisabled());
    }

    private Node chartHolderChild() {
        return root.lookupAll(".label").stream()
                .filter(n -> n instanceof Label l && l.getText() != null && l.getText().startsWith("“"))
                .findFirst().orElseThrow();
    }

    private void kind(ReportsPanel.Kind kind) {
        onFx(() -> combo(KIND).getSelectionModel().select(kind));
    }

    /** Pictures of every kind, light and dark, when asked for. */
    private void shots() throws Exception {
        String to = System.getenv("BM_REPORTS_SHOTS");
        if (to == null || to.isBlank()) return;
        Path out = Files.createDirectories(Path.of(to));
        for (BlockTheme.ThemeType theme : List.of(BlockTheme.ThemeType.DEFAULT, BlockTheme.ThemeType.DARK)) {
            onFx(() -> {
                BlockTheme.setTheme(theme);
                ThemedWindows.applyThemeClass(root);
            });
            for (ReportsPanel.Kind kind : ReportsPanel.Kind.values()) {
                boolean overDays = kind == ReportsPanel.Kind.BARS_BY_DAY || kind == ReportsPanel.Kind.LINES;
                onFx(() -> combo(GROUP_BY).getSelectionModel().select(overDays ? 3 : 0)); // Flow over days, else Bot line
                kind(kind);
                onFx(() -> panel.pick(overDays ? "Map" : "com.firestone.Daily:42"));
                snap(out.resolve(kind.name().toLowerCase().replace('_', '-') + "-"
                        + theme.name().toLowerCase() + ".png"));
            }
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
