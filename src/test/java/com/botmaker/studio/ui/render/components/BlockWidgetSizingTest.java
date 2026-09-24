package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A value on a block is drawn at its own width — a field at its text's, a dropdown at its choice's. */
class BlockWidgetSizingTest extends FxHeadlessTest {

    private HBox root;

    @Override
    public void start(Stage stage) {
        root = new HBox();
        stage.setScene(new Scene(root, 900, 200));
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

    @Test
    void aFieldIsAsWideAsItsText() throws Exception {
        double[] widths = new double[4];
        onFx(() -> {
            TextField digit = new TextField("0");
            TextField phrase = new TextField("a fairly long phrase");
            TextField huge = new TextField("x".repeat(500));
            TextField stock = new TextField("0");
            FieldSizing.fitToText(digit);
            FieldSizing.fitToText(phrase);
            FieldSizing.fitToText(huge);
            root.getChildren().setAll(digit, phrase, huge, stock);
            root.applyCss();
            root.layout();
            widths[0] = digit.prefWidth(-1);
            widths[1] = phrase.prefWidth(-1);
            widths[2] = huge.prefWidth(-1);
            widths[3] = stock.prefWidth(-1);
        });
        assertTrue(widths[0] < widths[3] / 2, "a 0 is much narrower than a stock 12-column field: " + widths[0]);
        assertTrue(widths[0] >= FieldSizing.MIN_WIDTH, "but still wide enough to click: " + widths[0]);
        assertTrue(widths[1] > widths[0] * 2, "a phrase is wider than a digit: " + widths[1]);
        assertEquals(FieldSizing.MAX_WIDTH, widths[2], 0.5, "a very long value scrolls inside its field");
    }

    @Test
    void aFieldGrowsAsItIsTypedIn() throws Exception {
        double[] widths = new double[2];
        onFx(() -> {
            TextField field = new TextField("1");
            FieldSizing.fitToText(field);
            root.getChildren().setAll(field);
            root.applyCss();
            widths[0] = field.prefWidth(-1);
            field.setText("100000");
            widths[1] = field.prefWidth(-1);
        });
        assertTrue(widths[1] > widths[0], "typing widens the field: " + widths[0] + " → " + widths[1]);
    }

    @Test
    void aDropdownIsAsWideAsItsChoiceNotItsLongestOption() throws Exception {
        double[] widths = new double[4];
        onFx(() -> {
            String[] options = {"plus", "greater than or equal to"};
            ComboBox<String> fitted = new FittedComboBox<>();
            ComboBox<String> stock = new ComboBox<>();
            fitted.getItems().setAll(options);
            stock.getItems().setAll(options);
            fitted.setValue("plus");
            stock.setValue("plus");
            root.getChildren().setAll(fitted, stock);
            root.applyCss();
            root.layout();
            widths[0] = fitted.prefWidth(-1);
            widths[1] = stock.prefWidth(-1);
            javafx.scene.text.Text plus = new javafx.scene.text.Text("plus");
            widths[2] = plus.getLayoutBounds().getWidth();
            fitted.setValue("greater than or equal to");
            root.layout();
            widths[3] = fitted.prefWidth(-1);
        });
        assertTrue(widths[0] < widths[1] - 40, "fitted " + widths[0] + " vs stock " + widths[1]);
        assertTrue(widths[0] > widths[2] + 10,
                "and never narrower than its own text plus the arrow — it collapsed to the arrow once: " + widths[0]);
        assertTrue(widths[3] > widths[0], "choosing a longer option widens it: " + widths[3]);
    }
}
