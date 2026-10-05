package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.Watched;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's pixels: a region's are the desktop's; the private session's are its own, from {@code 0,0}, and a
 * box in them is scaled onto the window that shows the session.
 */
class WatchedScreenTest {

    /** A session of {@code size}, showing {@code frame}, whose window is {@code windowId}. */
    private static WatchedScreen.LiveSession session(long windowId, Rectangle size, BufferedImage frame) {
        return new WatchedScreen.LiveSession() {
            @Override
            public long revealHostWindow() {
                return windowId;
            }

            @Override
            public Rectangle screen() {
                return size;
            }

            @Override
            public BufferedImage capture() {
                return frame;
            }
        };
    }

    @Test
    void aRegionIsInDesktopPixelsAndDrawnWhereItIs() {
        WatchedScreen region = WatchedScreen.resolve(Watched.region(new Area(100, 50, 640, 480)), null).orElseThrow();
        assertEquals(Optional.of(new Area(100, 50, 640, 480)), region.area());
        Area box = new Area(120, 60, 10, 10);
        assertEquals(Optional.of(box), region.toDesktop(box));
    }

    @Test
    void theSessionIsInItsOwnPixelsAndReadsItsOwnFrame() {
        BufferedImage frame = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        WatchedScreen screen = WatchedScreen.resolve(Watched.session(),
                session(42, new Rectangle(0, 0, 1920, 1080), frame)).orElseThrow();
        assertTrue(screen.isSession());
        assertEquals(Optional.of(new Area(0, 0, 1920, 1080)), screen.area(), "from 0,0 whatever window shows it");
        assertSame(frame, screen.frame().orElseThrow(), "the session's own frame, not a grab of its window");
    }

    @Test
    void noSessionRunningResolvesToNothing() {
        assertTrue(WatchedScreen.resolve(Watched.session(), session(0, null, null)).isEmpty());
        assertTrue(WatchedScreen.resolve(Watched.session(), null).isEmpty());
    }

    @Test
    void aSessionBoxIsScaledOntoItsWindow() {
        // A 1920×1080 session shown at half size in a window at 100,50.
        Area onDesktop = WatchedScreen.scaled(new Area(200, 100, 40, 20), new Rectangle(0, 0, 1920, 1080),
                new Rectangle(100, 50, 960, 540));
        assertEquals(new Area(200, 100, 20, 10), onDesktop);
    }
}
