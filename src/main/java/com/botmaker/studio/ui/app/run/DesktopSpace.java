package com.botmaker.studio.ui.app.run;

import java.util.List;

/**
 * Where the run layer goes and how it maps desktop pixels onto itself. JavaFX places windows in logical
 * coordinates, a screen's pixels being {@code scale} times as many; a plugin's layer node draws in desktop
 * pixels, the space {@code TraceLine.where} and the runtime's telemetry use. So the layer covers the union of
 * the screens' logical bounds, and its content is shifted by the union's pixel origin and shrunk by the scale.
 *
 * <p>One scale for the whole desktop: the primary screen's. X11 has one scale per desktop, so this is exact
 * there; where screens differ, a mark on a screen of another scale lands off by their ratio.
 *
 * @param x      the layer's left, in logical coordinates
 * @param y      its top
 * @param width  its logical width
 * @param height its logical height
 * @param scale  desktop pixels per logical unit
 */
record DesktopSpace(double x, double y, double width, double height, double scale) {

    /** A screen as JavaFX reports it: logical bounds and its output scale. */
    record Screen(double x, double y, double width, double height, double scale) {}

    /** The space over {@code screens}, the first being the primary; null when there are none. */
    static DesktopSpace of(List<Screen> screens) {
        if (screens == null || screens.isEmpty()) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Screen s : screens) {
            minX = Math.min(minX, s.x());
            minY = Math.min(minY, s.y());
            maxX = Math.max(maxX, s.x() + s.width());
            maxY = Math.max(maxY, s.y() + s.height());
        }
        double scale = screens.getFirst().scale() > 0 ? screens.getFirst().scale() : 1;
        return new DesktopSpace(minX, minY, maxX - minX, maxY - minY, scale);
    }

    /** The desktop pixel at the layer's top-left corner, along x. */
    double originPixelX() {
        return x * scale;
    }

    double originPixelY() {
        return y * scale;
    }

    /** Where desktop pixel ({@code px}, {@code py}) falls inside the layer, in its logical coordinates. */
    double[] toLayer(double px, double py) {
        return new double[] {(px - originPixelX()) / scale, (py - originPixelY()) / scale};
    }
}
