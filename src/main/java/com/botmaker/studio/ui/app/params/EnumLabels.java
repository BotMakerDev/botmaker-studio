package com.botmaker.studio.ui.app.params;

import java.util.Locale;

/**
 * An enum constant's name as a toggle shows it: {@code LEFT_CLICK} reads "Left click".
 *
 * <p>The host's own rule, applied to any enum by its name alone: a plugin's shorter label for its own type
 * ("Mon") is the plugin's to draw in its own editor, and Studio names no plugin type to ask for one. A name
 * that is not written in capitals is somebody's deliberate spelling and is left as it is.
 */
final class EnumLabels {

    private EnumLabels() {
    }

    static String label(String constant) {
        if (constant.isEmpty() || !constant.equals(constant.toUpperCase(Locale.ROOT))) return constant;
        String words = constant.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return words.isEmpty() ? constant : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
