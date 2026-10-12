package com.botmaker.studio.util;

import com.botmaker.shared.platform.Os;

/**
 * Opens a URL — a web page, or a {@code file:} folder for <i>Show in file manager</i> — with the platform's
 * opener ({@code xdg-open} / {@code open} / {@code rundll32}, {@link Os#openCommand}).
 *
 * <p><b>Never AWT, never on the caller's thread (2026-10-13).</b> This went through shared's
 * {@code UriLauncher}, which tries {@code Desktop.browse} first. Called from a context menu's action on Linux,
 * AWT's desktop peer started inside JavaFX's GTK process and hung the FX thread while the menu still held the
 * pointer grab: no window on the desktop took a click until Studio was killed. A process started on a daemon
 * thread cannot do that, and the platform opener is what {@code Desktop.browse} ends up calling anyway.
 * Best-effort: a failure the user can act on is printed rather than thrown.
 */
public final class BrowserLauncher {

    private BrowserLauncher() {}

    public static void open(String url) {
        if (url == null || url.isBlank()) return;
        Thread opener = new Thread(() -> {
            try {
                int exit = new ProcessBuilder(Os.current().openCommand(url)).inheritIO().start().waitFor();
                // xdg-open answers non-zero when nothing could open it (no handler, no such file).
                if (exit != 0) System.err.println("Nothing opened " + url + " (exit " + exit + ") — open it manually.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("Could not open " + url + " (" + e.getMessage() + ") — open it manually.");
            }
        }, "open-url");
        opener.setDaemon(true);
        opener.start();
    }
}
