package com.botmaker.studio.services;

import com.botmaker.studio.services.capture.ScreenOverlay;
import com.botmaker.studio.services.capture.TargetCapture;
import com.botmaker.studio.services.capture.TargetCapture.WindowRef;

import javafx.scene.image.Image;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editor-time screen capture, as one object — <b>a façade over two halves that are on their way apart.</b>
 *
 * <p>Until 2026-08-30 this was 1,324 lines in which resolving <em>which pixels</em> and deciding <em>what the
 * user does with them</em> were one flow. They are now {@link TargetCapture} and {@link ScreenOverlay}, and
 * the seam between them is a {@link com.botmaker.studio.services.capture.ScreenShot} — pixels, bounds, and
 * two flags. This class holds one of each and forwards, so that the split cost no call site a change.
 *
 * <p><b>It is scaffolding and it is meant to disappear.</b> What stays behind is the overlay, which is what
 * the plugin contract's {@code StudioServices.capture()} is implemented with; the remaining callers can
 * construct a {@link ScreenOverlay} and a {@link TargetCapture} directly.
 *
 * <p><b>Nothing here knows what a capture target is any more (2026-09-12).</b> {@code defaultTarget()},
 * {@code captureDefaultTargetAsync}, {@code forProjectFiles} and the {@code ProjectSettingsService}
 * constructor are gone with {@code CaptureTarget} itself: the target describes what the <b>bot</b> looks at,
 * lives in the owning plugin's {@code capture.json}, and Studio stopped reading it on 2026-08-31 — so every
 * one of them had answered "no target" for every caller since. What is left is a window a caller already
 * holds (raise, resize, capture, bounds, the title list) and the screen.
 *
 * <p>So: <b>add nothing here.</b> A new overlay behaviour belongs on {@link ScreenOverlay}, and anything
 * that needs a native window handle belongs on {@link TargetCapture}.
 */
public final class ScreenCaptureService {

    private final TargetCapture target;
    private final ScreenOverlay overlay;

    public ScreenCaptureService() {
        this.target = new TargetCapture();
        this.overlay = new ScreenOverlay(target);
    }

    // ── The overlay half ────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the interactive crop on the FX thread. The capture target is resolved from the project default
     * (a screen or a window); with multiple monitors and no default set the user first picks which screen.
     * The frame is shown 1:1 and the user rubber-bands a region. Calls {@code onCaptured} with the cropped
     * image, or does nothing if the user cancels (Esc / empty selection / chooser) or capture is unavailable.
     */
    public void captureRegion(Window owner, Consumer<BufferedImage> onCaptured) {
        overlay.captureRegion(owner, onCaptured);
    }

    /**
     * As {@link #captureRegion(Window, Consumer)} but also reports the capture source's physical resolution
     * (the full window/screen pixel size the region was cropped from) so the caller can record it as the
     * template's authored resolution.
     */
    public void captureRegion(Window owner, ScreenOverlay.RegionCapture onCaptured) {
        overlay.captureRegion(owner, onCaptured);
    }

    /**
     * Interactive rubber-band selection returning the chosen region as {@code [x, y, width, height]} in the
     * <b>capture source's</b> own pixel space. Does nothing if the user cancels or capture is unavailable.
     */
    public void selectRegion(Window owner, Consumer<int[]> onSelected) {
        overlay.selectRegion(owner, onSelected);
    }

    /** Interactive point pick with a magnified close-up, reporting {@code [x, y]} in the source's own space. */
    public void pickPoint(Window owner, Consumer<int[]> onPicked) {
        overlay.pickPoint(owner, onPicked);
    }

    /** The same magnified overlay, reporting the colour under the cursor rather than the coordinate. */
    public void pickColor(Window owner, Consumer<ScreenOverlay.ScreenPick> onPicked) {
        overlay.pickColor(owner, onPicked);
    }

    /**
     * Captures the target once and drives a single reusable overlay through {@code steps} in order — for a
     * whole method call's on-screen arguments.
     */
    public void runSession(Window owner, List<ScreenOverlay.PickStep> steps, Runnable onDone) {
        overlay.runSession(owner, steps, onDone);
    }

    /** Registers {@code listener}; returns a handle that unregisters it when closed. */
    public static AutoCloseable addCaptureOverlayListener(ScreenOverlay.CaptureOverlayListener listener) {
        return ScreenOverlay.addCaptureOverlayListener(listener);
    }

    /** The single {@code BufferedImage} → FX {@code Image} conversion in the application; null-tolerant. */
    public static Image toFxImage(BufferedImage image) {
        return ScreenOverlay.toFxImage(image);
    }

    // ── The target half ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Brings the window matching {@code target} to the front and captures its pixels, or {@code null} when no
     * window matches or capture fails.
     */
    public TargetCapture.WindowShot captureWindow(WindowRef windowTarget) {
        return target.captureWindow(windowTarget);
    }

    /** The current absolute bounds of the window matching {@code target}, or {@code null}. Cheap; FX-safe. */
    public static java.awt.Rectangle windowBounds(WindowRef windowTarget) {
        return TargetCapture.windowBounds(windowTarget);
    }

    /** Brings the window matching {@code target} to the front without capturing. Best-effort. */
    public void raiseWindow(WindowRef windowTarget) {
        target.raiseWindow(windowTarget);
    }

    /** Resizes the matching window to {@code width}×{@code height} logical px. Call off the FX thread. */
    public void resizeTarget(WindowRef windowTarget, int width, int height) {
        target.resizeTarget(windowTarget, width, height);
    }

    /** Enumerates the titles of the currently open windows (for the target chooser). Best-effort. */
    public static List<String> listWindowTitles() {
        return TargetCapture.listWindowTitles();
    }

    // ── Neither half ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes {@code image} to {@code target} as PNG, creating parent directories.
     *
     * <p>The one member here that belongs to neither half and so has nowhere to go yet: it is about files,
     * not about pixels or targets. It follows whichever caller is last to need it.
     */
    public void savePng(BufferedImage image, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }
}
