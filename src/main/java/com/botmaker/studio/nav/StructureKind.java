package com.botmaker.studio.nav;

/** What a File Structure row is, with the glyph the popup draws before it. */
public enum StructureKind {
    TYPE("type", "Class", "◆"),
    FIELD("field", "Field", "▪"),
    CONSTRUCTOR("constructor", "Constructor", "✚"),
    METHOD("method", "Function", "ƒ"),
    ENUM_CONSTANT("enum-constant", "Enum constant", "•");

    private final String id;
    private final String displayName;
    private final String glyph;

    StructureKind(String id, String displayName, String glyph) {
        this.id = id;
        this.displayName = displayName;
        this.glyph = glyph;
    }

    public String id() { return id; }

    public String displayName() { return displayName; }

    public String glyph() { return glyph; }
}
