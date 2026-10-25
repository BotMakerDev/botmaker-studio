package com.botmaker.studio.ui.app.botinfo;

import com.botmaker.studio.services.capture.ScreenOverlay;
import com.botmaker.studio.ui.app.StudioWindow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The square crop an icon goes through (2026-10-25, plan phase 4): the picture with a square on it, the largest
 * centred one at first. Dragging inside the square moves it; dragging outside draws a new one from that corner.
 */
final class SquareCrop {

    /** The longest side the picture is shown at. */
    private static final double VIEW = 480;

    /** The smallest square, in picture pixels: an icon cut from less is a blur. */
    private static final int MIN_SIDE = 16;

    private SquareCrop() {}

    /** Shows {@code picture} to crop; {@code done} gets the square cut, or nothing when the user cancels. */
    static void show(Window owner, BufferedImage picture, Consumer<Optional<BufferedImage>> done) {
        double scale = Math.min(1, VIEW / Math.max(picture.getWidth(), picture.getHeight()));
        double w = picture.getWidth() * scale;
        double h = picture.getHeight() * scale;
        ImageView image = new ImageView(ScreenOverlay.toFxImage(picture));
        image.setFitWidth(w);
        image.setFitHeight(h);

        double side = Math.min(w, h);
        Rectangle square = new Rectangle((w - side) / 2, (h - side) / 2, side, side);
        square.getStyleClass().add("bot-icon-crop");
        square.setMouseTransparent(true);
        Pane canvas = new Pane(image, square);
        canvas.setMinSize(w, h);
        canvas.setMaxSize(w, h);
        drag(canvas, square, w, h, Math.min(MIN_SIDE * scale, side));   // a picture under 16 px: all of it

        StudioWindow window = StudioWindow.modal("bot-icon-crop", "Crop the icon", owner);
        boolean[] answered = {false};
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> window.stage().close());
        Button use = new Button("Use this square");
        use.setDefaultButton(true);
        use.setOnAction(e -> {
            answered[0] = true;
            int x = clamp((int) Math.round(square.getX() / scale), picture.getWidth() - 1);
            int y = clamp((int) Math.round(square.getY() / scale), picture.getHeight() - 1);
            int s = Math.min((int) Math.round(square.getWidth() / scale),
                    Math.min(picture.getWidth() - x, picture.getHeight() - y));
            window.stage().close();
            done.accept(Optional.of(picture.getSubimage(x, y, s, s)));
        });
        window.stage().setOnHidden(e -> {
            if (!answered[0]) done.accept(Optional.empty());
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, use);
        Label hint = new Label("Drag the square to move it, or drag elsewhere to draw a new one.");
        VBox root = new VBox(10, hint, canvas, buttons);
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(12));
        window.show(root);
    }

    /** Moves {@code square} by a drag that starts inside it, else draws one from where the drag starts. */
    private static void drag(Pane canvas, Rectangle square, double w, double h, double minSide) {
        double[] from = new double[4];   // press x, y; square x, y at the press
        boolean[] moving = {false};
        canvas.setOnMousePressed(e -> {
            from[0] = e.getX();
            from[1] = e.getY();
            from[2] = square.getX();
            from[3] = square.getY();
            moving[0] = square.contains(e.getX(), e.getY());
        });
        canvas.setOnMouseDragged(e -> {
            if (moving[0]) {
                square.setX(Math.clamp(from[2] + e.getX() - from[0], 0, w - square.getWidth()));
                square.setY(Math.clamp(from[3] + e.getY() - from[1], 0, h - square.getHeight()));
                return;
            }
            double dx = e.getX() - from[0];
            double dy = e.getY() - from[1];
            double room = Math.min(dx < 0 ? from[0] : w - from[0], dy < 0 ? from[1] : h - from[1]);
            double s = Math.max(minSide, Math.min(Math.max(Math.abs(dx), Math.abs(dy)), room));
            square.setWidth(s);
            square.setHeight(s);
            square.setX(Math.clamp(dx < 0 ? from[0] - s : from[0], 0, w - s));
            square.setY(Math.clamp(dy < 0 ? from[1] - s : from[1], 0, h - s));
        });
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }
}
