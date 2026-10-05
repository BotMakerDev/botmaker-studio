package com.botmaker.studio.ui.app.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where the run layer sits and where a desktop pixel lands on it. */
class DesktopSpaceTest {

    @Test
    void oneScreenAtScaleOneIsTheIdentity() {
        DesktopSpace space = DesktopSpace.of(List.of(new DesktopSpace.Screen(0, 0, 1920, 1080, 1)));
        assertEquals(new DesktopSpace(0, 0, 1920, 1080, 1), space);
        assertArrayEquals(new double[] {640, 480}, space.toLayer(640, 480));
    }

    @Test
    void aHiDpiScreenHalvesThePixels() {
        // 3840x2160 pixels at 200%: JavaFX sees 1920x1080.
        DesktopSpace space = DesktopSpace.of(List.of(new DesktopSpace.Screen(0, 0, 1920, 1080, 2)));
        assertArrayEquals(new double[] {1000, 500}, space.toLayer(2000, 1000));
    }

    @Test
    void theLayerCoversEveryScreenAndStartsAtTheirTopLeft() {
        // A second screen to the left of the primary, lower down.
        DesktopSpace space = DesktopSpace.of(List.of(
                new DesktopSpace.Screen(0, 0, 1920, 1080, 1),
                new DesktopSpace.Screen(-1280, 200, 1280, 1024, 1)));
        assertEquals(new DesktopSpace(-1280, 0, 3200, 1224, 1), space);
        assertArrayEquals(new double[] {0, 0}, space.toLayer(-1280, 0));
        assertArrayEquals(new double[] {1280, 0}, space.toLayer(0, 0));
    }

    @Test
    void noScreenNoLayer() {
        assertNull(DesktopSpace.of(List.of()));
    }

    @Test
    void anUnknownScaleCountsAsOne() {
        assertEquals(1, DesktopSpace.of(List.of(new DesktopSpace.Screen(0, 0, 10, 10, 0))).scale());
    }
}
