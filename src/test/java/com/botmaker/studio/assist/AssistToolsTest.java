package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** list_palette: an index that hides no name, then lines a filter of any word finds. */
class AssistToolsTest {

    private static final List<PaletteEntry> PALETTE = List.of(
            new PaletteEntry("block:IF", PaletteEntry.Kind.STATEMENT, "If", "Control", List.of(), ""),
            new PaletteEntry("block:WHILE", PaletteEntry.Kind.STATEMENT, "While loop", "Control", List.of(), ""),
            new PaletteEntry("block:PRINT", PaletteEntry.Kind.STATEMENT, "Print", "Output", List.of(), ""),
            call("ImageFinder", "whileFindAny", "boolean", "ImageTemplateGroup", "Consumer"),
            call("ImageFinder", "untilFind", "Match", "ImageTemplate"),
            call("ImageFinder", "untilFind", "Match", "ImageTemplate", "Duration"),
            call("ImageWaiter", "waitUntilGone", "boolean", "ImageTemplate"),
            call("Mouse", "click", "void", "Point"));

    private static PaletteEntry call(String owner, String name, String returns, String... params) {
        return new PaletteEntry("call:" + owner + "." + name + "(" + String.join(",", params) + ")",
                PaletteEntry.Kind.CALL, owner + "." + name, "SDK", List.of(params), returns);
    }

    @Test
    void anEmptyFilterGivesEveryNameByClass() {
        String index = AssistTools.listed(PALETTE, " , ");
        assertEquals("""
                Control (2 statements): IF, WHILE
                Output (1 statement): PRINT
                ImageFinder (3 calls): whileFindAny, untilFind ×2
                ImageWaiter (1 call): waitUntilGone
                Mouse (1 call): click
                Filter by a class or a word for each entry's id, parameters and return type.""", index);
        assertEquals(index, AssistTools.listed(PALETTE, null));
        for (PaletteEntry e : PALETTE) {
            String bare = e.id().replaceFirst("^\\w+:(\\w+\\.)?", "").replaceFirst("\\(.*", "");
            assertTrue(index.contains(bare), "the index names " + e.id());
        }
    }

    @Test
    void aFilterFindsByClassTypeOrAnyOfItsWords() {
        assertEquals("""
                call:ImageFinder.whileFindAny(ImageTemplateGroup,Consumer) → boolean
                call:ImageFinder.untilFind(ImageTemplate) → Match
                call:ImageFinder.untilFind(ImageTemplate,Duration) → Match""",
                AssistTools.listed(PALETTE, "imagefinder"), "a class, any case");
        assertEquals("call:Mouse.click(Point) → void", AssistTools.listed(PALETTE, "Point"), "a parameter type");
        assertEquals("""
                call:ImageFinder.untilFind(ImageTemplate) → Match
                call:ImageFinder.untilFind(ImageTemplate,Duration) → Match""",
                AssistTools.listed(PALETTE, "match"), "a return type");
        assertEquals("""
                block:WHILE — While loop (Control)
                call:ImageWaiter.waitUntilGone(ImageTemplate) → boolean""",
                AssistTools.listed(PALETTE, ",loop, gone"), "words are OR'd, a statement by its label");
        assertEquals("block:PRINT — Print (Output)", AssistTools.listed(PALETTE, "output"),
                "an index line's first word finds it");
        assertEquals("Nothing matches call. An empty filter gives every name.", AssistTools.listed(PALETTE, "call"),
                "the id's prefix matches nothing");
        assertEquals("Nothing matches Keyboard. An empty filter gives every name.",
                AssistTools.listed(PALETTE, "Keyboard"));
    }
}
