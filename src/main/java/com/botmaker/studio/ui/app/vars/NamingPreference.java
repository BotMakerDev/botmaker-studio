package com.botmaker.studio.ui.app.vars;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.prefs.Preferences;

/**
 * Whether inserting a block that declares a name asks for it first (View ▸ Ask for Names When Inserting). On by
 * default. Off, a new variable or enum takes the free default name (`number2`, `MyEnum2`) and nothing opens:
 * for someone placing many blocks, one dialog per block is a click each they did not ask for.
 *
 * <p>Static, like {@code BlockStylePreference}: the View menu and every canvas read one value.
 */
public final class NamingPreference {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "ui.askForNames";

    private static final BooleanProperty ASK = new SimpleBooleanProperty(load());

    static {
        ASK.addListener((obs, was, now) -> save(now));
    }

    private NamingPreference() {}

    /** Bind a check menu item to this; setting it remembers the choice. */
    public static BooleanProperty askProperty() {
        return ASK;
    }

    public static boolean ask() {
        return ASK.get();
    }

    private static boolean load() {
        try {
            return Preferences.userRoot().node(PREFS_NODE).getBoolean(PREFS_KEY, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void save(boolean ask) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.putBoolean(PREFS_KEY, ask);
            prefs.flush();
        } catch (Exception e) {
            // A choice that is not remembered still applies for this session.
        }
    }
}
