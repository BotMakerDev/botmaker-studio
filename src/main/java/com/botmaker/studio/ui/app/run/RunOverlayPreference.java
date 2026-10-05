package com.botmaker.studio.ui.app.run;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.OptionalDouble;
import java.util.prefs.Preferences;

/**
 * Whether a run opens the {@link RunOverlay} (View ▸ Show Run Overlay, on by default), and where the user last
 * left its bar. Static, like {@code NamingPreference}: the View menu and the overlay read one value.
 */
public final class RunOverlayPreference {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String SHOWN_KEY = "run.overlay";
    private static final String BAR_X_KEY = "run.overlay.barX";
    private static final String BAR_Y_KEY = "run.overlay.barY";

    private static final BooleanProperty SHOWN = new SimpleBooleanProperty(load());

    static {
        SHOWN.addListener((obs, was, now) -> put(p -> p.putBoolean(SHOWN_KEY, now)));
    }

    private RunOverlayPreference() {}

    public static BooleanProperty shownProperty() {
        return SHOWN;
    }

    public static boolean shown() {
        return SHOWN.get();
    }

    /** Where the bar was left, in screen coordinates; empty until it has been placed once. */
    public static OptionalDouble barX() {
        return read(BAR_X_KEY);
    }

    public static OptionalDouble barY() {
        return read(BAR_Y_KEY);
    }

    public static void saveBar(double x, double y) {
        put(p -> {
            p.putDouble(BAR_X_KEY, x);
            p.putDouble(BAR_Y_KEY, y);
        });
    }

    private static boolean load() {
        try {
            return Preferences.userRoot().node(PREFS_NODE).getBoolean(SHOWN_KEY, true);
        } catch (RuntimeException | Error e) {
            return true;
        }
    }

    private static OptionalDouble read(String key) {
        try {
            double value = Preferences.userRoot().node(PREFS_NODE).getDouble(key, Double.NaN);
            return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
        } catch (RuntimeException | Error e) {
            return OptionalDouble.empty();
        }
    }

    private static void put(java.util.function.Consumer<Preferences> write) {
        try {
            write.accept(Preferences.userRoot().node(PREFS_NODE));
        } catch (RuntimeException | Error e) {
            // no preference store: the choice holds for this session only
        }
    }
}
