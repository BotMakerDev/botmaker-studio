package com.botmaker.studio.services.capture;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;

import javafx.stage.Screen;
import javafx.stage.Window;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * <b>Which pixels</b> — the half of editor-time capture that knows what a capture target is.
 *
 * <p>It grabs a window a caller names — raising and focusing it first — or the screen, and it owns everything
 * else that needs a native window handle: bounds probes, raise, resize, the title list the overlay editor's
 * picker offers.
 *
 * <p><b>It resolves no capture target, and since 2026-09-12 it holds no notion of one.</b> It used to switch
 * on the project's configured default — window, monitor, desktop, emulator over ADB — and that default has
 * answered {@code null} for every caller since Studio stopped reading {@code capture.json} on 2026-08-31,
 * because the file describes what the <b>bot</b> looks at and belongs to the plugin that owns
 * {@code CaptureSource}. So `CaptureTarget`, the supplier and the emulator branch are gone: what is left is
 * what a caller with a window in hand asks for.
 *
 * <p><b>This class is on its way out of Studio, and the split that isolates it is the point.</b> A capture
 * target is the SDK's vocabulary rather than an editor's — a window to look at is what a bot's own
 * {@code CaptureSource} names — so everything here belongs to the plugin that owns that vocabulary. What
 * stays behind is {@link ScreenOverlay}, which consumes a {@link ScreenShot} and never asks where it came
 * from. Split, the move becomes a move; unsplit, it was 1,324 lines with no seam in them.
 *
 * <p>It implements {@link ShotSource}, which is the whole of what the overlay needs from it — and therefore
 * the whole of what has to exist on the far side afterwards.
 *
 * <h2>One thing worth knowing before changing anything here</h2>
 *
 * <p><b>A window capture that comes back blank falls through to a desktop crop.</b> Native per-window capture
 * returns black under Wayland; the fallback grabs the whole desktop (which has a CLI path there) and crops it
 * to the window's bounds.
 */
public final class TargetCapture implements ShotSource {

    /**
     * A window to act on, named by title and — when the caller has one — by its exact native handle.
     *
     * <p><b>The id is why this is not a stored capture target.</b> A stored target's identity is its spec
     * text, and an id belongs to one live process: persisting one is meaningless, which is exactly what
     * {@code window:<title>} says by having nowhere to put it. But a caller that has just launched a window
     * <em>does</em> know which one it means, and a title cannot always say — a gamescope session renames its
     * host window after whatever app is inside it and shares its {@code WM_CLASS} with a second, unmanaged
     * window of its own.
     *
     * <p>So the handle lives here, beside the code that resolves it, for the length of one session. A stale id
     * is not an error: {@link #resolveWindow(WindowRef)} falls back to the title, which makes carrying an id no
     * worse than not carrying one.
     */
    public record WindowRef(String titleSubstring, Long windowId) {

        /** A window named the ordinary way: by title alone, which is every persisted target. */
        public WindowRef(String titleSubstring) {
            this(titleSubstring, null);
        }
    }

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


    /** A captured window frame plus its absolute screen bounds (for overlay placement + coordinate mapping). */
    public record WindowShot(BufferedImage image, java.awt.Rectangle bounds) {}

    /**
     * Brings the window matching {@code target} to the front and captures its pixels. Returns
     * {@code null} if no window matches or capture fails. The window is focused first — both to satisfy
     * "move the chosen window to front" and because the Robot-based capture paths need it visible. If the
     * native per-window capture yields a blank frame (e.g. native Wayland, where Robot returns black),
     * this falls back to a full-desktop grab cropped to the window bounds (Wayland-capable, lossless).
     */
    public WindowShot captureWindow(WindowRef target) {
        GenericWindow win = resolveWindow(target);
        if (win == null) {
            System.err.println("No window matching \"" + target.titleSubstring() + "\" was found.");
            return null;
        }
        NativeController controller = NativeControllerFactory.get();
        // Restore (de-iconify if minimized) + raise + focus, then let the compositor settle before grabbing.
        try {
            controller.restoreWindow(win);
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("Could not focus window: " + t.getMessage());
        }
        // Re-resolve after focus (bounds may change when a minimized window is restored/raised).
        GenericWindow refreshed = resolveWindow(target);
        if (refreshed != null) win = refreshed;
        java.awt.Rectangle bounds = win.getRect();

        BufferedImage img = null;
        try {
            img = controller.captureWindow(win);
        } catch (Throwable t) {
            System.err.println("Native window capture failed: " + t.getMessage());
        }

        if (img == null || DesktopGrab.looksBlank(img)) {
            // Fallback: full-desktop grab (handles Wayland via CLI tools) cropped to the window bounds.
            try {
                BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                BufferedImage cropped = (desktop == null) ? null : cropToBounds(desktop, bounds);
                if (cropped != null) img = cropped;
            } catch (Exception e) {
                System.err.println("Desktop-crop fallback failed: " + e.getMessage());
            }
        }
        return img == null ? null : new WindowShot(img, bounds);
    }

    /**
     * The window {@code target} names: its exact {@link WindowRef#windowId() windowId} when it carries one and
     * that window still exists, otherwise its title substring.
     *
     * <p>The fallback is the whole point of the id being optional. An id belongs to one live process — a session
     * that has since been closed, or a settings file written yesterday, leaves an id that resolves to nothing, and
     * a target that silently captures nothing is the failure mode this service is worst at reporting. Falling back
     * to the title makes a stale id no worse than not having one.
     */
    private static GenericWindow resolveWindow(WindowRef target) {
        if (target == null) return null;
        Long id = target.windowId();
        if (id != null) {
            GenericWindow byId = resolveWindowById(id);
            if (byId != null) return byId;
        }
        return resolveWindow(target.titleSubstring());
    }

    /** The window whose native handle is {@code windowId}, or {@code null} when no live window has that id. */
    private static GenericWindow resolveWindowById(long windowId) {
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows(true)) {
                if (nativeIdOf(w) == windowId) return w;
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return null;
    }

    /** A window's platform handle as a plain long — a JNA {@code Pointer} on X11, a {@code Number} elsewhere. */
    private static long nativeIdOf(GenericWindow w) {
        Object handle = w.getNativeHandle();
        if (handle instanceof com.sun.jna.Pointer p) return com.sun.jna.Pointer.nativeValue(p);
        return handle instanceof Number n ? n.longValue() : 0;
    }

    /**
     * First window (case-insensitive) whose title contains {@code titleSubstring}, or {@code null}. Includes
     * currently-minimized windows so a minimized target can be found and then de-iconified/raised.
     */
    private static GenericWindow resolveWindow(String titleSubstring) {
        if (titleSubstring == null) return null;
        String needle = titleSubstring.toLowerCase();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows(true)) {
                String t = w.getTitle();
                if (t != null && t.toLowerCase().contains(needle)) return w;
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * The current absolute bounds of the window matching {@code target}, or {@code null} when no window
     * matches. A bounds-only probe: unlike {@link #captureWindow} it neither raises the window, nor sleeps for
     * the compositor, nor grabs any pixels, so it is cheap enough to call from the FX thread. That is what the
     * overlay recorder needs when a session starts — the origin its coordinates are relative to, nothing else.
     */
    public static java.awt.Rectangle windowBounds(WindowRef target) {
        GenericWindow win = resolveWindow(target);
        return win == null ? null : win.getRect();
    }

    /**
     * Brings the window matching {@code target} to the front (de-iconifying if minimized) without capturing.
     * Best-effort; used by the macro recorder so the target is raised when recording begins.
     */
    public void raiseWindow(WindowRef target) {
        GenericWindow win = resolveWindow(target);
        if (win == null) return;
        try {
            NativeControllerFactory.get().restoreWindow(win);
        } catch (Throwable t) {
            System.err.println("Could not raise window: " + t.getMessage());
        }
    }

    /**
     * Resizes the window matching {@code target} to {@code width}×{@code height} (logical px) and lets the
     * compositor settle, so a template is captured at the project's canonical resolution rather than whatever
     * size the window happens to be. Best-effort no-op when the window can't be found or is already that size.
     * Runs synchronously; call it off the FX thread (it sleeps briefly).
     */
    public void resizeTarget(WindowRef target, int width, int height) {
        if (width <= 0 || height <= 0) return;
        GenericWindow win = resolveWindow(target);
        if (win == null) return;
        java.awt.Rectangle r = win.getRect();
        if (r != null && r.width == width && r.height == height) return; // already canonical
        try {
            NativeControllerFactory.get().resizeWindow(win, width, height);
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("Could not resize window: " + t.getMessage());
        }
    }

    /** Enumerates the titles of the currently open windows (for the target chooser). Best-effort. */
    public static List<String> listWindowTitles() {
        List<String> titles = new java.util.ArrayList<>();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows()) {
                String t = w.getTitle();
                if (t != null && !t.isBlank() && !titles.contains(t)) titles.add(t);
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return titles;
    }

    /**
     * Crops the full-desktop {@code desktop} image to absolute-screen {@code bounds}. Maps absolute
     * coordinates to desktop-image pixels via the AWT virtual-screen origin (union of all devices).
     * Assumes scale 1.0 (same caveat as {@link #cropToScreen}); this is only the blank-frame fallback.
     */
    private static BufferedImage cropToBounds(BufferedImage desktop, java.awt.Rectangle bounds) {
        java.awt.Rectangle virtual = new java.awt.Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            virtual = virtual.union(gd.getDefaultConfiguration().getBounds());
        }
        int x = Math.max(0, Math.min(bounds.x - virtual.x, desktop.getWidth() - 1));
        int y = Math.max(0, Math.min(bounds.y - virtual.y, desktop.getHeight() - 1));
        int w = Math.max(1, Math.min(bounds.width, desktop.getWidth() - x));
        int h = Math.max(1, Math.min(bounds.height, desktop.getHeight() - y));
        return desktop.getSubimage(x, y, w, h);
    }
}
