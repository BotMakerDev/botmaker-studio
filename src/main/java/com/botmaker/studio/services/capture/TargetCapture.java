package com.botmaker.studio.services.capture;

import javafx.stage.Screen;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * <b>Which pixels</b> for the overlay's screen grab: the desktop, cropped to the one monitor there is, or handed to
 * the FX-thread chooser when there are several. It needs JavaFX's screens, so it stays Studio's; frames of a window
 * or a part of the desktop are {@link Capture}'s (split in plan phase 7), which knows no window of Studio's own.
 *
 * <p><b>It resolves no capture target, and since 2026-09-12 it holds no notion of one.</b> It used to switch
 * on the project's configured default — window, monitor, desktop, emulator over ADB — and that default has
 * answered {@code null} for every caller since Studio stopped reading {@code capture.json} on 2026-08-31,
 * because the file describes what the <b>bot</b> looks at and belongs to the plugin that owns
 * {@code CaptureSource}.
 *
 * <p>It implements {@link ShotSource}, which is the whole of what {@link ScreenOverlay} needs from it — and therefore
 * the whole of what has to exist on the far side afterwards.
 */
public final class TargetCapture implements ShotSource {

    @Override
    public Grab grab(Window owner) {
        return grabOffThread();
    }

    /**
     * The window title a template captured through this source records, or {@code null}.
     *
     * <p>Always {@code null} now, and it has been in effect since 2026-08-31: the answer came from the
     * project's configured capture target, which is the owning plugin's and which Studio does not read. The
     * method stays because {@link ShotSource} is the contract {@link ScreenOverlay} consumes, and a capture
     * source that <em>does</em> know its window is exactly what a plugin implementing that interface would
     * supply.
     */
    @Override
    public String title() {
        return null;
    }

    /**
     * Grabs the screen (blocking — call off the FX thread only).
     *
     * <ul>
     *   <li>one monitor → the whole virtual desktop;</li>
     *   <li>several monitors → the desktop image, for the FX-thread chooser to ask which one.</li>
     * </ul>
     *
     * <p>It used to switch on the project's default capture target first — window, monitor, desktop, chooser.
     * Studio holds no capture target, so every pick asks, which is the honest behaviour for an editor that
     * does not know what the bot watches. A source that <em>does</em> know is a plugin's to supply through
     * {@link ShotSource}.
     */
    private Grab grabOffThread() {
        BufferedImage desktop;
        try {
            desktop = DesktopGrab.grabVirtualDesktop();
        } catch (Exception e) {
            System.err.println("Screen capture failed: " + e.getMessage());
            return new Grab(null, null);
        }
        if (desktop == null) return new Grab(null, null);
        boolean blank = DesktopGrab.looksBlank(desktop);

        List<Screen> screens = Screen.getScreens();
        if (screens.size() > 1) {
            return new Grab(null, desktop); // no default to consult → FX-thread chooser
        }
        Screen screen = Screen.getPrimary();
        return new Grab(new ScreenShot(Screens.cropToScreen(desktop, screens, screen), screen.getBounds(), true, blank), null);
    }
}
