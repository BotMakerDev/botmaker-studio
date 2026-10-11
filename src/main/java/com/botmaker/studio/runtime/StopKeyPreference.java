package com.botmaker.studio.runtime;

import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.input.KeyNames;
import com.botmaker.shared.platform.Os;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;

import java.util.prefs.Preferences;

/**
 * The key that stops a running bot from anywhere on the desktop (View ▸ Stop Key…), as an X keysym — on macOS
 * too, where the listener reports the same keysyms. Pause by default: it is on most keyboards and almost nothing
 * binds it, and the listener is passive, so whatever has focus receives the key too. A Mac keyboard has no
 * Pause, so there the default is F12, which macOS binds to nothing (a laptop sends it with fn held).
 *
 * <p>Static, like {@code NamingPreference}: the View menu and {@link StopKey} read one value.
 */
public final class StopKeyPreference {

    /** {@code XK_Pause}. */
    public static final long PAUSE = 0xFF13;
    /** {@code XK_F12}. */
    public static final long F12 = 0xFFC9;
    /** The key a user who never chose one gets: Pause, or F12 on a Mac. */
    public static final long DEFAULT = Os.current().isMac() ? F12 : PAUSE;
    /** {@code XK_Escape}: never offered, since most programs a bot drives use it. */
    public static final long ESCAPE = 0xFF1B;

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "run.stopKey";

    private static final LongProperty KEYSYM = new SimpleLongProperty(load());

    static {
        KEYSYM.addListener((obs, was, now) -> save(now.longValue()));
    }

    private StopKeyPreference() {}

    public static LongProperty keysymProperty() {
        return KEYSYM;
    }

    public static long keysym() {
        return KEYSYM.get();
    }

    /**
     * Whether {@code keysym} may be the stop key: not Esc, and not a modifier (Shift, Control, Caps Lock, Alt,
     * Super, AltGr, Num Lock). A modifier is pressed on the way to a combination, so taking it would store
     * Control the moment the user starts typing Ctrl+F12 — and a bot that sends Ctrl+C would then stop itself.
     */
    public static boolean offerable(long keysym) {
        boolean modifier = (keysym >= 0xFFE1 && keysym <= 0xFFEE) // Shift_L … Hyper_R
                || keysym == 0xFE03 // ISO_Level3_Shift (AltGr)
                || keysym == 0xFF7E // Mode_switch
                || keysym == 0xFF7F; // Num_Lock
        return keysym > 0 && keysym != ESCAPE && !modifier;
    }

    /** The current key's name, as the menu and the status line say it. */
    public static String name() {
        return nameOf(keysym());
    }

    /**
     * {@code XKeysymToString} — {@code Pause}, {@code F8}, {@code Scroll_Lock} — with underscores read as
     * spaces; on macOS, or where libX11 is missing, the same names from {@link KeyNames}; the hex code where
     * neither has one.
     */
    public static String nameOf(long keysym) {
        if (Os.current().isLinux()) {
            try {
                String name = X11.INSTANCE.XKeysymToString((int) keysym);
                if (name != null && !name.isBlank()) return name.replace('_', ' ');
            } catch (Throwable unavailable) {
                // no libX11: fall through to the table
            }
        }
        String name = KeyNames.name(keysym);
        return name != null ? name : String.format("key 0x%X", keysym);
    }

    private static long load() {
        try {
            long stored = Preferences.userRoot().node(PREFS_NODE).getLong(PREFS_KEY, DEFAULT);
            return offerable(stored) ? stored : DEFAULT;
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    private static void save(long keysym) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.putLong(PREFS_KEY, keysym);
            prefs.flush();
        } catch (Exception e) {
            // A choice that is not remembered still applies for this session.
        }
    }
}
