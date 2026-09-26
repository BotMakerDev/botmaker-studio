package com.botmaker.studio.ui.app.versions;

import java.util.prefs.Preferences;

/**
 * Whether the Versions tab's function cards show only what changed. Remembered per user, beside
 * {@link VersionsView}, for the same reason: it is about who is looking, not the project being looked at.
 */
final class OnlyDifferences {

    private static final String PREFS_NODE = "com/botmaker/studio/versions";
    private static final String PREFS_KEY = "onlyDifferences";

    private OnlyDifferences() {}

    /** What this user last chose; off by default. */
    static boolean remembered() {
        try {
            return Preferences.userRoot().node(PREFS_NODE).getBoolean(PREFS_KEY, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void remember(boolean only) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.putBoolean(PREFS_KEY, only);
            prefs.flush();
        } catch (Exception e) {
            // A choice that is not remembered is still applied.
        }
    }
}
