package com.botmaker.studio.ui.render.theme;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStyleTest {

    @Test
    void aSavedStyleReadsBackAsItself() {
        for (BlockStyle style : BlockStyle.values()) assertEquals(style, BlockStyle.fromId(style.id()));
    }

    @Test
    void anythingElseReadsAsTheDefault() {
        assertEquals(BlockStyle.DEFAULT, BlockStyle.fromId(null));
        assertEquals(BlockStyle.DEFAULT, BlockStyle.fromId(""));
        assertEquals(BlockStyle.DEFAULT, BlockStyle.fromId("FILLED"), "the id, not the enum name, is what is saved");
        assertEquals(BlockStyle.DEFAULT, BlockStyle.fromId("glass"), "a style a newer Studio saved");
        assertEquals(BlockStyle.FILLED, BlockStyle.DEFAULT, "Scratch-like filled is the default look");
    }

    @Test
    void theStylesHaveDistinctIdsAndCanvasClasses() {
        Set<String> ids = new HashSet<>();
        Set<String> classes = new HashSet<>();
        for (BlockStyle style : BlockStyle.values()) {
            assertTrue(ids.add(style.id()), "duplicate id " + style.id());
            assertTrue(classes.add(style.styleClass()), "duplicate class " + style.styleClass());
            assertTrue(style.styleClass().startsWith("blocks-"), style.styleClass());
        }
        assertEquals(classes, Set.copyOf(BlockStyle.styleClasses()));
    }
}
