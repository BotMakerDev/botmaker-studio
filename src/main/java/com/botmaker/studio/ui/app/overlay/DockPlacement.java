package com.botmaker.studio.ui.app.overlay;

import javafx.geometry.Rectangle2D;

import java.awt.Rectangle;
import java.util.List;

/**
 * Where the overlay editor's panel docks: beside the watched screen's right edge, else its left edge, else over
 * its right side when neither has room — on the screen that holds most of it, as tall as it is but never
 * shorter than {@link #MIN_HEIGHT}. Pure, so the rule is tested without a toolkit.
 */
final class DockPlacement {

    /** The gap between the watched screen and the panel. */
    static final double GAP = 6;
    /** The panel is never shorter than this, so a small game window still leaves room for the script. */
    static final double MIN_HEIGHT = 560;

    private DockPlacement() {}

    /**
     * The panel's bounds beside {@code watched}, {@code width} wide, on one of {@code screens} (their visual
     * bounds, the primary first).
     */
    static Rectangle2D dock(Rectangle watched, List<Rectangle2D> screens, double width) {
        Rectangle2D screen = screenOf(watched, screens);
        double height = Math.min(screen.getHeight(), Math.max(MIN_HEIGHT, watched.height));
        double y = Math.max(screen.getMinY(), Math.min(watched.y, screen.getMaxY() - height));
        double right = watched.x + watched.width + GAP;
        double left = watched.x - GAP - width;
        double x;
        if (right + width <= screen.getMaxX()) x = right;
        else if (left >= screen.getMinX()) x = left;
        else x = screen.getMaxX() - width;
        return new Rectangle2D(x, y, width, height);
    }

    /** The screen holding most of {@code watched}; the first when it is on none. */
    private static Rectangle2D screenOf(Rectangle watched, List<Rectangle2D> screens) {
        Rectangle2D best = screens.getFirst();
        double most = -1;
        for (Rectangle2D s : screens) {
            double w = Math.min(s.getMaxX(), watched.x + watched.width) - Math.max(s.getMinX(), watched.x);
            double h = Math.min(s.getMaxY(), watched.y + watched.height) - Math.max(s.getMinY(), watched.y);
            double overlap = Math.max(0, w) * Math.max(0, h);
            if (overlap > most) {
                most = overlap;
                best = s;
            }
        }
        return best;
    }
}
