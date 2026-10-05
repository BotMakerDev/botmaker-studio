package com.botmaker.studio.ui.app.overlay;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The panel docks right of the watched window, else left, else over its right side, on that window's screen. */
class DockPlacementTest {

    private static final List<Rectangle2D> ONE = List.of(new Rectangle2D(0, 0, 1920, 1040));

    @Test
    void rightWhenThereIsRoom() {
        Rectangle2D at = DockPlacement.dock(new Rectangle(100, 50, 1280, 720), ONE, 380);
        assertEquals(100 + 1280 + DockPlacement.GAP, at.getMinX());
        assertEquals(50, at.getMinY());
        assertEquals(720, at.getHeight(), "as tall as the window");
    }

    @Test
    void leftWhenTheRightIsFullAndOverTheWindowWhenBothAre() {
        Rectangle2D left = DockPlacement.dock(new Rectangle(600, 0, 1300, 900), ONE, 380);
        assertEquals(600 - DockPlacement.GAP - 380, left.getMinX());

        Rectangle2D over = DockPlacement.dock(new Rectangle(0, 0, 1920, 1040), ONE, 380);
        assertEquals(1920 - 380, over.getMinX());
        assertEquals(1040, over.getHeight(), "never taller than the screen");
    }

    @Test
    void aSmallWindowStillGetsARoomyPanelOnItsOwnScreen() {
        List<Rectangle2D> two = List.of(new Rectangle2D(0, 0, 1920, 1040), new Rectangle2D(1920, 0, 1920, 1040));
        Rectangle2D at = DockPlacement.dock(new Rectangle(2000, 900, 400, 300), two, 380);
        assertEquals(2000 + 400 + DockPlacement.GAP, at.getMinX());
        assertEquals(DockPlacement.MIN_HEIGHT, at.getHeight());
        assertEquals(1040 - DockPlacement.MIN_HEIGHT, at.getMinY(), "moved up to stay on the screen");
    }
}
