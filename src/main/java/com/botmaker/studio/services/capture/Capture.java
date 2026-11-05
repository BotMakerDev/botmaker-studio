package com.botmaker.studio.services.capture;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.capture.WindowMatch;
import com.sun.jna.Pointer;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Frames of the desktop and its windows, with no window of Studio's own in reach: a window a caller names (raised and
 * focused first, or peeked as it is), a part of the desktop, bounds probes, raise, and the title list the overlay
 * editor's picker offers. Split from {@link TargetCapture} in plan phase 7, which keeps what needs JavaFX's screens (the
 * monitor chooser's grab); this half is what the core captures with.
 *
 * <p><b>A window capture that comes back blank falls through to a desktop crop.</b> Native per-window capture returns
 * black under Wayland; the fallback grabs the whole desktop (which has a CLI path there) and crops it to the window's
 * bounds.
 */
public final class Capture {

    private Capture() {
    }

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

    /** A captured window frame plus its absolute screen bounds (for overlay placement + coordinate mapping). */
    public record WindowShot(BufferedImage image, Rectangle bounds) {}

    /**
     * Brings the window matching {@code target} to the front and captures its pixels. Returns
     * {@code null} if no window matches or capture fails. The window is focused first — both to satisfy
     * "move the chosen window to front" and because the Robot-based capture paths need it visible. If the
     * native per-window capture yields a blank frame (e.g. native Wayland, where Robot returns black),
     * this falls back to a full-desktop grab cropped to the window bounds (Wayland-capable, lossless).
     */
    public static WindowShot captureWindow(WindowRef target) {
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
        return grab(win);
    }

    /**
     * The window matching {@code target} as it is now, without raising, focusing or waiting for it — for a
     * caller that grabs again and again while the user works, such as the overlay editor's tools. A window
     * covered by another one may show what covers it. {@code null} when no window matches or capture fails.
     */
    public static WindowShot peekWindow(WindowRef target) {
        GenericWindow win = resolveWindow(target);
        return win == null ? null : grab(win);
    }

    /**
     * The part of the desktop {@code bounds} covers, in desktop pixels, or {@code null} when the desktop cannot
     * be grabbed.
     */
    public static BufferedImage grabArea(Rectangle bounds) {
        try {
            BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
            return DesktopGrab.cropToBounds(desktop, bounds);
        } catch (Exception e) {
            System.err.println("Desktop grab failed: " + e.getMessage());
            return null;
        }
    }

    /** {@code win}'s pixels, falling back to a desktop crop when the native grab is blank. */
    private static WindowShot grab(GenericWindow win) {
        NativeController controller = NativeControllerFactory.get();
        Rectangle bounds = win.getRect();

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
                BufferedImage cropped = DesktopGrab.cropToBounds(desktop, bounds);
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
        if (handle instanceof Pointer p) return Pointer.nativeValue(p);
        return handle instanceof Number n ? n.longValue() : 0;
    }

    /**
     * First window (case-insensitive) whose title contains {@code titleSubstring}, or {@code null}. Includes
     * currently-minimized windows so a minimized target can be found and then de-iconified/raised. One of
     * BotMaker's own windows matches only a needle that names BotMaker: "Firestone" found Studio's "BotMaker
     * Blocks - Firestone" while the game ran in a private display, and the assistant was shown Studio
     * (2026-10-13; shared's {@code WindowMatch} skips them the same way).
     */
    private static GenericWindow resolveWindow(String titleSubstring) {
        if (titleSubstring == null) return null;
        String needle = titleSubstring.toLowerCase();
        boolean own = needle.trim().startsWith(WindowMatch.OWN_TITLE_PREFIX);
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows(true)) {
                String t = w.getTitle();
                if (t != null && t.toLowerCase().contains(needle) && (own || !WindowMatch.isOwn(w))) return w;
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
    public static Rectangle windowBounds(WindowRef target) {
        GenericWindow win = resolveWindow(target);
        return win == null ? null : win.getRect();
    }

    /**
     * Brings the window matching {@code target} to the front (de-iconifying if minimized) without capturing.
     * Best-effort; used by the macro recorder so the target is raised when recording begins.
     */
    public static void raiseWindow(WindowRef target) {
        GenericWindow win = resolveWindow(target);
        if (win == null) return;
        try {
            NativeControllerFactory.get().restoreWindow(win);
        } catch (Throwable t) {
            System.err.println("Could not raise window: " + t.getMessage());
        }
    }

    /** Enumerates the titles of the currently open windows (for the target chooser). Best-effort. */
    public static List<String> listWindowTitles() {
        List<String> titles = new ArrayList<>();
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
}
