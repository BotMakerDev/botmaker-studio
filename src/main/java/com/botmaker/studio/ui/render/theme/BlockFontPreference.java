package com.botmaker.studio.ui.render.theme;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.prefs.Preferences;

/**
 * Which {@link BlockFont} every canvas is written in. A user preference beside the block style, static for the
 * reason {@link BlockStylePreference} gives: the View menu and every open canvas read one value.
 */
public final class BlockFontPreference {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "ui.blockFont";

    private static final ReadOnlyObjectWrapper<BlockFont> FONT = new ReadOnlyObjectWrapper<>(load());

    private BlockFontPreference() {}

    /** The current font. Listen to it — weakly, from anything rebuilt per project — and set it with {@link #set}. */
    public static ReadOnlyObjectProperty<BlockFont> fontProperty() {
        return FONT.getReadOnlyProperty();
    }

    public static BlockFont font() {
        return FONT.get();
    }

    /** Switches every canvas to {@code font}, and remembers it. A null is the default. */
    public static void set(BlockFont font) {
        BlockFont chosen = font == null ? BlockFont.DEFAULT : font;
        if (chosen.equals(FONT.get())) return;
        FONT.set(chosen);
        save(chosen);
    }

    private static BlockFont load() {
        try {
            return BlockFont.fromId(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, BlockFont.DEFAULT.id()));
        } catch (RuntimeException e) {
            return BlockFont.DEFAULT;
        }
    }

    private static void save(BlockFont font) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, font.id());
            prefs.flush();
        } catch (Exception e) {
            // A font that is not remembered is still applied.
        }
    }
}
