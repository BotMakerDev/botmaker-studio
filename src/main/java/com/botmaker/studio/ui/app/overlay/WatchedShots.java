package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.services.capture.DesktopGrab;
import com.botmaker.studio.services.capture.Grab;
import com.botmaker.studio.services.capture.ScreenShot;
import com.botmaker.studio.services.capture.ShotSource;
import com.botmaker.studio.services.overlay.WatchedScreen;
import javafx.geometry.Rectangle2D;
import javafx.stage.Window;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The watched screen as a {@link ShotSource}, so a tool's pick is drawn over exactly what the bot watches and
 * not over a screen chooser. The shot is drawn over where the screen sits on the desktop, and a pick answers in
 * the shot's own pixels; {@link #origin()} is where the last shot sat in the bot's pixels, which turns a pick into
 * the bot's pixels the contract speaks — desktop pixels, or for the session the session's own.
 */
final class WatchedShots implements ShotSource {

    private final Supplier<WatchedScreen> screen;
    private volatile Rectangle origin;

    WatchedShots(Supplier<WatchedScreen> screen) {
        this.screen = screen;
    }

    @Override
    public Grab grab(Window owner) {
        WatchedScreen watched = screen.get();
        Rectangle bounds = watched == null ? null : watched.bounds();
        Optional<Area> area = bounds == null ? Optional.empty() : watched.area();
        Optional<BufferedImage> frame = area.isEmpty() ? Optional.empty() : watched.frame();
        if (frame.isEmpty()) return new Grab(null, null);
        Area at = area.get();
        origin = new Rectangle(at.x(), at.y(), at.width(), at.height());
        BufferedImage image = frame.get();
        return new Grab(new ScreenShot(image, new Rectangle2D(bounds.x, bounds.y, bounds.width, bounds.height),
                false, DesktopGrab.looksBlank(image)), null);
    }

    @Override
    public String title() {
        WatchedScreen watched = screen.get();
        return watched == null ? null : watched.windowRef().map(r -> r.titleSubstring()).orElse(null);
    }

    /** Where the last shot sat in the bot's pixels; {@code 0,0} before any. */
    Rectangle origin() {
        Rectangle at = origin;
        return at == null ? new Rectangle() : at;
    }
}
