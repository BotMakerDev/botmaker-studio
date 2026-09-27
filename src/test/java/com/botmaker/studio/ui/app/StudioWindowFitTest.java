package com.botmaker.studio.ui.app;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A remembered window comes back whole on the screen it overlaps (2026-09-27). It was restored wherever it was
 * left as long as a corner showed, so a window saved on a wide monitor opened with its right half — and the
 * buttons there — past the edge of a smaller one.
 */
class StudioWindowFitTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 0, 1280, 800);

    @Test
    void aWindowHangingOffTheEdgeIsPulledBackWhole() {
        assertEquals(new Rectangle2D(320, 0, 960, 689),
                StudioWindow.fitInto(new Rectangle2D(531, 0, 960, 689), SCREEN));
    }

    @Test
    void aWindowLargerThanTheScreenIsShrunkToIt() {
        assertEquals(new Rectangle2D(0, 0, 1280, 800),
                StudioWindow.fitInto(new Rectangle2D(-50, -20, 1600, 900), SCREEN));
    }

    @Test
    void aWindowAlreadyInsideIsLeftAlone() {
        Rectangle2D inside = new Rectangle2D(100, 50, 900, 640);
        assertEquals(inside, StudioWindow.fitInto(inside, SCREEN));
    }

    @Test
    void aSecondScreenKeepsItsOwnOrigin() {
        Rectangle2D right = new Rectangle2D(1280, 0, 1920, 1080);
        assertEquals(new Rectangle2D(2240, 100, 960, 640),
                StudioWindow.fitInto(new Rectangle2D(2900, 100, 960, 640), right));
    }
}
