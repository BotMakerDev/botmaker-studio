package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.overlay.Marks;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.services.overlay.WatchedScreen;

import java.util.function.Supplier;

/**
 * {@link Marks} in the bot's pixels, drawn on the desktop layer: each box is mapped through the watched screen
 * ({@link WatchedScreen#toDesktop}), which for the private session scales it onto the window showing it. A box
 * that cannot be placed now — the window closed — is not drawn. Any thread; mapping the session's reads the
 * window's bounds.
 */
final class BotPixelMarks implements Marks {

    private final Marks desktop;
    private final Supplier<WatchedScreen> screen;

    BotPixelMarks(Marks desktop, Supplier<WatchedScreen> screen) {
        this.desktop = desktop;
        this.screen = screen;
    }

    @Override
    public void show(Area area, Kind kind, String label) {
        WatchedScreen watched = screen.get();
        if (watched == null) return;
        watched.toDesktop(area).ifPresent(at -> desktop.show(at, kind, label));
    }

    @Override
    public void clear() {
        desktop.clear();
    }
}
