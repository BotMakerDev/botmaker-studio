package com.botmaker.studio.ui.render.theme;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;

import java.util.prefs.Preferences;

/**
 * How large the block canvas is drawn: one factor, {@value #MIN} to {@value #MAX}, for every project.
 *
 * <p>A <b>user preference, not a project setting</b> — the same kind of thing as the theme, stored beside it
 * in {@link Preferences} and static for the same reason {@link BlockTheme} is: the View menu and every open
 * canvas read one value, and a project that remembered its own zoom would open at a size chosen on somebody
 * else's screen.
 *
 * <p>The steps are fixed stops, not a multiply: {@link #zoomIn()} from a pinch-made {@code 1.37} lands on
 * {@code 1.4}, so the menu always gets back to a round number and to 100% exactly.
 */
public final class CanvasZoom {

    public static final double MIN = 0.5;
    public static final double MAX = 2.0;
    public static final double DEFAULT = 1.0;

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "ui.canvasZoom";

    private static final ReadOnlyDoubleWrapper FACTOR = new ReadOnlyDoubleWrapper(load());

    private CanvasZoom() {}

    /** The current factor, {@code 1.0} being 100%. Listen to it; set it through the methods below. */
    public static ReadOnlyDoubleProperty factorProperty() {
        return FACTOR.getReadOnlyProperty();
    }

    public static double factor() {
        return FACTOR.get();
    }

    public static void zoomIn() {
        set(stepUp(factor()));
    }

    public static void zoomOut() {
        set(stepDown(factor()));
    }

    public static void reset() {
        set(DEFAULT);
    }

    /** Sets any factor — a pinch passes its own — clamped, and remembered. */
    public static void set(double factor) {
        double clamped = clamp(factor);
        if (clamped == FACTOR.get()) return;
        FACTOR.set(clamped);
        save(clamped);
    }

    /** {@code factor} held inside {@link #MIN}..{@link #MAX}; a non-number is the default. */
    static double clamp(double factor) {
        if (Double.isNaN(factor) || Double.isInfinite(factor)) return DEFAULT;
        return Math.max(MIN, Math.min(MAX, factor));
    }

    /** The next stop strictly above {@code factor}, capped at {@link #MAX}. */
    public static double stepUp(double factor) {
        return clamp(stop(Math.floor(tenths(factor)) + 1));
    }

    /** The next stop strictly below {@code factor}, floored at {@link #MIN}. */
    public static double stepDown(double factor) {
        return clamp(stop(Math.ceil(tenths(factor)) - 1));
    }

    /** A stop as a clean decimal: {@code 11} is {@code 1.1}, never {@code 1.1000000000000001}. */
    private static double stop(double tenths) {
        return Math.round(tenths) / 10.0;
    }

    /**
     * {@code factor} counted in tenths, with float noise rounded away — {@code 0.7 / 0.1} is
     * {@code 6.999…}, which floors to the stop below and would make {@link #zoomIn()} stand still.
     */
    private static double tenths(double factor) {
        return Math.round(factor * 10 * 1_000_000) / 1_000_000.0;
    }

    /** The factor as a label: {@code 1.0} → {@code "100%"}. */
    public static String percent(double factor) {
        return Math.round(factor * 100) + "%";
    }

    private static double load() {
        try {
            return clamp(Preferences.userRoot().node(PREFS_NODE).getDouble(PREFS_KEY, DEFAULT));
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    private static void save(double factor) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.putDouble(PREFS_KEY, factor);
            prefs.flush();
        } catch (Exception e) {
            // A zoom that is not remembered is still a zoom.
        }
    }
}
