package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.Watched;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.services.capture.TargetCapture;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The screen the overlay editor is drawn beside: a window, or a part of the desktop — what a plugin's
 * {@link Watched} names, resolved against what is on the desktop now.
 *
 * <p>Its bounds and frame are read live, since the window moves while the panel is up. Both reach the native
 * window list, so call them off the FX thread when called often.
 */
public final class WatchedScreen {

    private final String label;
    private final TargetCapture.WindowRef window;
    private final Rectangle region;

    private WatchedScreen(String label, TargetCapture.WindowRef window, Rectangle region) {
        this.label = label;
        this.window = window;
        this.region = region;
    }

    /** The window {@code ref} names, captioned {@code label}. */
    public static WatchedScreen window(TargetCapture.WindowRef ref, String label) {
        return new WatchedScreen(label, ref, null);
    }

    /** The private display session's host window, whose id is {@code windowId}. */
    public static WatchedScreen session(long windowId) {
        return window(new TargetCapture.WindowRef("private session", windowId), "the private display session");
    }

    /**
     * What {@code watched} names on the desktop now, or empty when it names nothing open: a window no open
     * window's title contains, a session with no session running.
     *
     * @param sessionWindow the live session's host window id, or {@code 0} for none
     */
    public static Optional<WatchedScreen> resolve(Watched watched, LongSupplier sessionWindow) {
        if (watched == null) return Optional.empty();
        return switch (watched.kind()) {
            case SESSION -> {
                long id = sessionWindow == null ? 0 : sessionWindow.getAsLong();
                yield id == 0 ? Optional.empty() : Optional.of(session(id));
            }
            case WINDOW -> watched.title().flatMap(title -> {
                TargetCapture.WindowRef ref = new TargetCapture.WindowRef(title);
                return TargetCapture.windowBounds(ref) == null ? Optional.empty()
                        : Optional.of(window(ref, "\"" + title + "\""));
            });
            case REGION -> watched.area().map(a -> new WatchedScreen(
                    "the region " + a.x() + "," + a.y() + " " + a.width() + "×" + a.height(), null,
                    new Rectangle(a.x(), a.y(), a.width(), a.height())));
        };
    }

    /** What the panel's header calls it. */
    public String label() {
        return label;
    }

    /** The window, when this is one. */
    public Optional<TargetCapture.WindowRef> windowRef() {
        return Optional.ofNullable(window);
    }

    /** Where it is on the desktop now, in desktop pixels; {@code null} when its window has closed. */
    public Rectangle bounds() {
        return window == null ? new Rectangle(region) : TargetCapture.windowBounds(window);
    }

    /** {@link #bounds()} as the contract's area, empty when its window has closed. */
    public Optional<Area> area() {
        Rectangle r = bounds();
        return r == null ? Optional.empty() : Optional.of(new Area(r.x, r.y, r.width, r.height));
    }

    /** What it shows now, without raising it; empty when it cannot be grabbed. Blocking. */
    public Optional<BufferedImage> frame() {
        if (window == null) return Optional.ofNullable(TargetCapture.grabArea(region));
        TargetCapture.WindowShot shot = TargetCapture.peekWindow(window);
        return shot == null ? Optional.empty() : Optional.ofNullable(shot.image());
    }

    /** Whether this and {@code other} are the same screen: one window, or one region. */
    public boolean sameAs(WatchedScreen other) {
        return other != null && java.util.Objects.equals(window, other.window)
                && java.util.Objects.equals(region, other.region);
    }
}
