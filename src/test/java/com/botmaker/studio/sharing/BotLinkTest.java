package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A website's Open in Studio link (2026-10-25): {@code botmaker://run/<owner>/<repo>} and nothing else. */
class BotLinkTest {

    @Test
    void aGoodLinkNamesItsBot() {
        assertEquals(Optional.of(new BotLink("LiQiyeDev", "Firestone")),
                BotLink.parse("botmaker://run/LiQiyeDev/Firestone"));
        assertEquals(Optional.of(new BotLink("some-one", "my_bot.v2")), BotLink.parse("BotMaker://RUN/some-one/my_bot.v2"),
                "the scheme and host without case");
        assertEquals("botmaker://run/LiQiyeDev/Firestone", new BotLink("LiQiyeDev", "Firestone").toString());
    }

    @Test
    void aWrongSchemeOrHostIsRefused() {
        assertTrue(BotLink.parse("https://run/LiQiyeDev/Firestone").isEmpty());
        assertTrue(BotLink.parse("botmaker://get/LiQiyeDev/Firestone").isEmpty());
        assertTrue(BotLink.parse("botmaker:run/LiQiyeDev/Firestone").isEmpty());
        assertTrue(BotLink.parse("botmaker://runx/LiQiyeDev/Firestone").isEmpty());
        assertTrue(BotLink.parse(null).isEmpty());
    }

    @Test
    void aPathOfOtherThanTwoPartsIsRefused() {
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev").isEmpty());
        assertEquals(Optional.of(new BotLink("LiQiyeDev", "Firestone")),
                BotLink.parse("botmaker://run/LiQiyeDev/Firestone/"), "one trailing slash, as some browsers add");
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/Firestone//").isEmpty());
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/Firestone/releases").isEmpty());
        assertTrue(BotLink.parse("botmaker://run//Firestone").isEmpty());
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/..").isEmpty());
    }

    @Test
    void encodedCharactersQueriesAndOddNamesAreRefused() {
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/Fire%73tone").isEmpty());
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev%2FFirestone/x").isEmpty());
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/Firestone?url=https://evil").isEmpty());
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/Firestone#x").isEmpty());
        assertTrue(BotLink.parse("botmaker://run/-owner/Firestone").isEmpty(), "no hyphen first");
        assertTrue(BotLink.parse("botmaker://run/a--b/Firestone").isEmpty(), "no double hyphen");
        assertTrue(BotLink.parse("botmaker://run/LiQiyeDev/Fire stone").isEmpty());
    }

    @Test
    void theCommandLineHandsTheFirstLinkOverEvenARefusedOne() {
        assertEquals(Optional.of("botmaker://run/a/b"), BotLink.asked(List.of("--x=1", "botmaker://run/a/b")));
        assertEquals(Optional.of("BOTMAKER://nope"), BotLink.asked(List.of("BOTMAKER://nope")));
        assertTrue(BotLink.asked(List.of("/tmp/Firestone.bmk")).isEmpty());
    }

    @Test
    void itNamesItsCatalogEntryWithoutCase() {
        GalleryEntry entry = new GalleryEntry("Firestone", "LiQiyeDev", "Firestone", "", List.of(), "", "1.0.2",
                PublisherRank.ADMIN, 0, List.of(), PublisherRank.ADMIN, List.of());
        assertTrue(new BotLink("liqiyedev", "FIRESTONE").names(entry));
        assertTrue(!new BotLink("LiQiyeDev", "Firestone2").names(entry));
    }
}
