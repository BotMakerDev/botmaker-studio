package com.botmaker.studio.ui.app.versions;

import java.util.Arrays;
import java.util.prefs.Preferences;

/**
 * The Versions tab's two views ({@code docs/refactor/39-versions.md} §9): <i>Simple</i> for someone who saves
 * and shares, <i>Dev</i> for someone who reads git. Remembered per user, in Studio's preferences — not per
 * project, since it is about who is looking, not what they look at.
 */
public enum VersionsView {
    SIMPLE("simple", "Simple"),
    DEV("dev", "Dev");

    private static final String PREFS_NODE = "com/botmaker/studio/versions";
    private static final String PREFS_KEY = "view";

    private final String id;
    private final String displayName;

    VersionsView(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The view named {@code id}; anything else is {@link #SIMPLE}, the default. */
    public static VersionsView fromId(String id) {
        return Arrays.stream(values()).filter(v -> v.id.equals(id)).findFirst().orElse(SIMPLE);
    }

    /** The view this user last chose. */
    public static VersionsView remembered() {
        try {
            return fromId(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, SIMPLE.id));
        } catch (RuntimeException e) {
            return SIMPLE;
        }
    }

    /** Remembers {@code view} for the next tab this user opens. */
    public static void remember(VersionsView view) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, view.id);
            prefs.flush();
        } catch (Exception e) {
            // A view that is not remembered is still shown.
        }
    }
}
