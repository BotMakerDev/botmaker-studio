package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A suggestion's title as its branch name, {@code suggest/<slug>}. */
class SuggestionTest {

    @Test
    void aTitleBecomesAShortLowerCaseBranchName() {
        assertEquals("faster-mining-in-caves", Suggestion.slug("Faster mining — in caves!"));
        assertEquals("change", Suggestion.slug("  ✨  "));
        assertEquals("change", Suggestion.slug(null));
        String longOne = Suggestion.slug("a very long title that goes on and on well past forty characters");
        assertEquals(true, longOne.length() <= 40 && !longOne.endsWith("-"), longOne);
    }
}
