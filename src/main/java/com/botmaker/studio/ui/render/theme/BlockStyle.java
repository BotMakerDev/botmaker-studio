package com.botmaker.studio.ui.render.theme;

import java.util.List;

/**
 * How a block is painted: its category's colour <em>filled</em> in, or a neutral card <em>outlined</em> in it.
 *
 * <p>Orthogonal to the theme. A theme says what the colours are; a style says what is done with them, so each
 * of the four themes is drawn both ways and neither choice constrains the other. The style is one class on the
 * canvas root ({@link #styleClass()}) and every rule that differs between the two is written under it in
 * {@code blocks.css}; nothing in Java paints a block. See {@code docs/refactor/37-block-styling.md}.
 */
public enum BlockStyle {
    /** Scratch-like: the whole block in its category's colour, a darker lip under it, C-shaped round bodies. */
    FILLED("filled", "Filled", "blocks-filled"),
    /** A card in the canvas's own colour, the category shown as a bar down its left edge. */
    OUTLINED("outlined", "Outlined", "blocks-outlined");

    /** What a canvas is drawn in when nothing was chosen, or when what was saved is no longer a style. */
    public static final BlockStyle DEFAULT = FILLED;

    private final String id;
    private final String displayName;
    private final String styleClass;

    BlockStyle(String id, String displayName, String styleClass) {
        this.id = id;
        this.displayName = displayName;
        this.styleClass = styleClass;
    }

    /** The stable key a preference is saved under — never the enum name, which a rename would break. */
    public String id() {
        return id;
    }

    /** What the View menu calls it. */
    public String displayName() {
        return displayName;
    }

    /** The class on the canvas root that selects this style's rules in {@code blocks.css}. */
    public String styleClass() {
        return styleClass;
    }

    /** Every class a style may have put on a node, for swapping one for another. */
    public static List<String> styleClasses() {
        return java.util.Arrays.stream(values()).map(BlockStyle::styleClass).toList();
    }

    /**
     * The style saved as {@code id}, or {@link #DEFAULT}. Total, and deliberately without an {@code UNKNOWN}:
     * a canvas has to be drawn some way, and a preference a newer Studio wrote is best read as the default.
     */
    public static BlockStyle fromId(String id) {
        for (BlockStyle style : values()) {
            if (style.id.equals(id)) return style;
        }
        return DEFAULT;
    }
}
