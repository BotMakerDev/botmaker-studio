package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.Watched;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.services.capture.TargetCapture;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;

/**
 * The screen the overlay editor is drawn beside: a window, a part of the desktop, or the private display
 * session — what a plugin's {@link Watched} names, resolved against what is on the desktop now.
 *
 * <p><b>Two spaces.</b> {@link #bounds()} is where it sits on the desktop, which the panel docks beside.
 * {@link #area()} and {@link #frame()} are in <em>the bot's pixels</em>, the space its clicks land in and the
 * contract's positions use. For a window or a region the two are the same. For the session they are not: the
 * session is a display of its own, at the project's resolution, shown on the desktop by a window that may be
 * moved, clipped or scaled — so its frame is the session's own and its area starts at {@code 0,0}, and a mark is
 * mapped onto the window to be drawn ({@link #toDesktop}).
 *
 * <p>Bounds and frames are read live, since the window moves while the panel is up. Both reach the native
 * window list, so call them off the FX thread when called often.
 */
public final class WatchedScreen {

    /** The live private display session, as the overlay reads it. */
    public interface LiveSession {
        /** Its host window's id, revealed when minimised; {@code 0} when no session runs. Has that side effect. */
        long revealHostWindow();

        /** Its own size, in its own pixels; null when it is not running. */
        Rectangle screen();

        /**
         * Its whole screen now, from {@code 0,0} in its own pixels — not the attached window alone, which sits
         * somewhere inside it; null when it cannot be read. Blocking.
         */
        BufferedImage capture();
    }

    private final String label;
    private final TargetCapture.WindowRef window;
    private final Rectangle region;
    private final LiveSession session;

    private WatchedScreen(String label, TargetCapture.WindowRef window, Rectangle region, LiveSession session) {
        this.label = label;
        this.window = window;
        this.region = region;
        this.session = session;
    }

    /** The window {@code ref} names, captioned {@code label}. */
    public static WatchedScreen window(TargetCapture.WindowRef ref, String label) {
        return new WatchedScreen(label, ref, null, null);
    }

    /** The private display session, shown on the desktop by the window whose id is {@code windowId}. */
    public static WatchedScreen session(long windowId, LiveSession session) {
        return new WatchedScreen("the private display session",
                new TargetCapture.WindowRef("private session", windowId), null, Objects.requireNonNull(session));
    }

    /**
     * What {@code watched} names on the desktop now, or empty when it names nothing open: a window no open
     * window's title contains, a session with no session running.
     *
     * @param session the live session, or null for none — asking it reveals its window
     */
    public static Optional<WatchedScreen> resolve(Watched watched, LiveSession session) {
        if (watched == null) return Optional.empty();
        return switch (watched.kind()) {
            case SESSION -> {
                long id = session == null ? 0 : session.revealHostWindow();
                yield id == 0 ? Optional.empty() : Optional.of(session(id, session));
            }
            case WINDOW -> watched.title().flatMap(title -> {
                TargetCapture.WindowRef ref = new TargetCapture.WindowRef(title);
                return TargetCapture.windowBounds(ref) == null ? Optional.empty()
                        : Optional.of(window(ref, "\"" + title + "\""));
            });
            case REGION -> watched.area().map(a -> new WatchedScreen(
                    "the region " + a.x() + "," + a.y() + " " + a.width() + "×" + a.height(), null,
                    new Rectangle(a.x(), a.y(), a.width(), a.height()), null));
        };
    }

    /** What the panel's header calls it. */
    public String label() {
        return label;
    }

    /** The window, when this is one; for the session, the window showing it. */
    public Optional<TargetCapture.WindowRef> windowRef() {
        return Optional.ofNullable(window);
    }

    /** Whether this is the private display session. */
    public boolean isSession() {
        return session != null;
    }

    /** Where it is on the desktop now, in desktop pixels; {@code null} when its window has closed. */
    public Rectangle bounds() {
        return window == null ? new Rectangle(region) : TargetCapture.windowBounds(window);
    }

    /**
     * Where {@link #frame()} sits in the bot's pixels: its desktop bounds, or for the session {@code 0,0} and the
     * session's size. Empty when its window has closed or the session stopped.
     */
    public Optional<Area> area() {
        Rectangle r = session != null ? session.screen() : bounds();
        if (r == null) return Optional.empty();
        return Optional.of(session != null ? new Area(0, 0, r.width, r.height) : new Area(r.x, r.y, r.width, r.height));
    }

    /** What it shows now, in the bot's pixels, without raising it; empty when it cannot be grabbed. Blocking. */
    public Optional<BufferedImage> frame() {
        if (session != null) return Optional.ofNullable(session.capture());
        if (window == null) return Optional.ofNullable(TargetCapture.grabArea(region));
        TargetCapture.WindowShot shot = TargetCapture.peekWindow(window);
        return shot == null ? Optional.empty() : Optional.ofNullable(shot.image());
    }

    /**
     * {@code area}, in the bot's pixels, as desktop pixels to draw it at; empty when it cannot be placed now.
     * The session's area is scaled onto the window that shows it. Reads the window's bounds.
     */
    public Optional<Area> toDesktop(Area area) {
        if (area == null) return Optional.empty();
        if (session == null) return Optional.of(area);
        Rectangle shown = bounds();
        Rectangle own = session.screen();
        return shown == null || own == null ? Optional.empty() : Optional.of(scaled(area, own, shown));
    }

    /** {@code area} in a {@code from}-sized space at {@code 0,0}, scaled and moved onto {@code onto}. */
    static Area scaled(Area area, Rectangle from, Rectangle onto) {
        double sx = from.width <= 0 ? 1 : (double) onto.width / from.width;
        double sy = from.height <= 0 ? 1 : (double) onto.height / from.height;
        return new Area((int) Math.round(onto.x + area.x() * sx), (int) Math.round(onto.y + area.y() * sy),
                (int) Math.round(area.width() * sx), (int) Math.round(area.height() * sy));
    }

    /** Whether this and {@code other} are the same screen: one window, or one region. */
    public boolean sameAs(WatchedScreen other) {
        return other != null && Objects.equals(window, other.window) && Objects.equals(region, other.region);
    }
}
