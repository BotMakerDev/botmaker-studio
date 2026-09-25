package com.botmaker.studio.ui.app.versions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublishSheetTagsTest {

    @Test
    void emptyForNullOrBlank() {
        assertEquals(List.of(), PublishSheet.parseTags(null));
        assertEquals(List.of(), PublishSheet.parseTags("   "));
        assertEquals(List.of(), PublishSheet.parseTags(",, ,"));
    }

    @Test
    void trimsSplitsAndDropsBlanks() {
        assertEquals(List.of("clicker", "farming"), PublishSheet.parseTags(" clicker , farming "));
        assertEquals(List.of("a", "b"), PublishSheet.parseTags("a,,b,"));
    }

    @Test
    void dedupesPreservingOrder() {
        assertEquals(List.of("clicker", "bot"), PublishSheet.parseTags("clicker, bot, clicker"));
    }
}
