package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.overlay.Marks;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

/**
 * The boxes the host draws on the desktop layer for {@link Marks}: where a probe found its picture, where a
 * click would land, what a tool picked. The look is the host's, one per {@link Marks.Kind}; a plugin says only
 * where, which kind and what label. Drawn in desktop pixels, inside the layer's desktop-pixel group.
 */
final class HostMarks {

    private static final Color FOUND = Color.web("#3fb950");
    private static final Color MISSING = Color.web("#f85149");
    private static final Color CLICK = Color.web("#f0883e");
    private static final Color NOTE = Color.web("#58a6ff");

    private final Pane pane = new Pane();

    HostMarks() {
        pane.setMouseTransparent(true);
        pane.setPickOnBounds(false);
    }

    /** What goes in the layer. */
    Node node() {
        return pane;
    }

    /** A handle whose {@link Marks#clear} removes only what it drew. Any thread. */
    Marks handle() {
        return new Marks() {
            private final List<Node> drawn = new ArrayList<>();

            @Override
            public void show(Area area, Kind kind, String label) {
                if (area == null) return;
                onFx(() -> {
                    Node node = box(area, kind == null ? Kind.NOTE : kind, label);
                    drawn.add(node);
                    pane.getChildren().add(node);
                });
            }

            @Override
            public void clear() {
                onFx(() -> {
                    pane.getChildren().removeAll(drawn);
                    drawn.clear();
                });
            }
        };
    }

    private static Node box(Area area, Marks.Kind kind, String label) {
        Color color = switch (kind) {
            case FOUND -> FOUND;
            case MISSING -> MISSING;
            case CLICK -> CLICK;
            case NOTE, UNKNOWN -> NOTE;
        };
        Rectangle box = new Rectangle(Math.max(1, area.width()), Math.max(1, area.height()));
        box.setFill(kind == Marks.Kind.CLICK ? color.deriveColor(0, 1, 1, 0.18) : Color.TRANSPARENT);
        box.setStroke(color);
        box.setStrokeWidth(3);
        if (kind == Marks.Kind.MISSING) box.getStrokeDashArray().setAll(10.0, 6.0);
        Group group = new Group(box);
        if (label != null && !label.isBlank()) {
            // Inside the box's top-left corner rather than above it: above could be off the top of a screen.
            Label caption = new Label(label);
            caption.setStyle("-fx-text-fill: white; -fx-padding: 1 4; -fx-background-color: "
                    + rgba(color.deriveColor(0, 1, 0.55, 0.88)) + ";");
            group.getChildren().add(caption);
        }
        group.relocate(area.x(), area.y());
        return group;
    }

    private static String rgba(Color c) {
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }
}
