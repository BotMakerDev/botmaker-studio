package com.botmaker.studio.ui.render.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasZoomTest {

    @Test
    void stepsLandOnRoundStops() {
        assertEquals(1.1, CanvasZoom.stepUp(1.0));
        assertEquals(0.9, CanvasZoom.stepDown(1.0));
        // A pinch leaves an arbitrary factor; the next step snaps to a stop rather than keeping the offset.
        assertEquals(1.4, CanvasZoom.stepUp(1.37));
        assertEquals(1.3, CanvasZoom.stepDown(1.37));
    }

    /** {@code 0.7 / 0.1} is {@code 6.999…}: a naive floor would make zoom-in stand still on 70%. */
    @Test
    void floatNoiseNeverStallsAStep() {
        for (int tenths = 5; tenths < 20; tenths++) {
            double stop = tenths / 10.0;
            assertEquals((tenths + 1) / 10.0, CanvasZoom.stepUp(stop), "up from " + stop);
        }
        for (int tenths = 20; tenths > 5; tenths--) {
            double stop = tenths / 10.0;
            assertEquals((tenths - 1) / 10.0, CanvasZoom.stepDown(stop), "down from " + stop);
        }
    }

    @Test
    void theRangeIsHeld() {
        assertEquals(CanvasZoom.MAX, CanvasZoom.stepUp(CanvasZoom.MAX));
        assertEquals(CanvasZoom.MIN, CanvasZoom.stepDown(CanvasZoom.MIN));
        assertEquals(CanvasZoom.MAX, CanvasZoom.clamp(9));
        assertEquals(CanvasZoom.MIN, CanvasZoom.clamp(0.01));
        assertEquals(CanvasZoom.DEFAULT, CanvasZoom.clamp(Double.NaN));
    }

    @Test
    void percentIsWhole() {
        assertEquals("100%", CanvasZoom.percent(1.0));
        assertEquals("150%", CanvasZoom.percent(1.5));
        assertEquals("137%", CanvasZoom.percent(1.37));
    }
}
