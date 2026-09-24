package com.botmaker.studio.ui.render.theme;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.prefs.Preferences;

/**
 * Which {@link BlockStyle} every canvas is drawn in. A user preference beside the theme and the zoom, static
 * for the reason {@link CanvasZoom} gives: the View menu and every open canvas read one value.
 */
public final class BlockStylePreference {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "ui.blockStyle";

    private static final ReadOnlyObjectWrapper<BlockStyle> STYLE = new ReadOnlyObjectWrapper<>(load());

    private BlockStylePreference() {}

    /** The current style. Listen to it — weakly, from anything rebuilt per project — and set it with {@link #set}. */
    public static ReadOnlyObjectProperty<BlockStyle> styleProperty() {
        return STYLE.getReadOnlyProperty();
    }

    public static BlockStyle style() {
        return STYLE.get();
    }

    /** Switches every canvas to {@code style}, and remembers it. A null is the default. */
    public static void set(BlockStyle style) {
        BlockStyle chosen = style == null ? BlockStyle.DEFAULT : style;
        if (chosen == STYLE.get()) return;
        STYLE.set(chosen);
        save(chosen);
    }

    private static BlockStyle load() {
        try {
            return BlockStyle.fromId(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, BlockStyle.DEFAULT.id()));
        } catch (RuntimeException e) {
            return BlockStyle.DEFAULT;
        }
    }

    private static void save(BlockStyle style) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, style.id());
            prefs.flush();
        } catch (Exception e) {
            // A style that is not remembered is still applied.
        }
    }
}
