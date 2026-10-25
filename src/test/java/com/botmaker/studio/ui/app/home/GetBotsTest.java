package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Get more bots' filter (2026-10-25): the query, the community choice, the hidden list. */
class GetBotsTest {

    static GalleryEntry bot(String name, String owner, PublisherRank rank, String description, String... tags) {
        return new GalleryEntry(name, owner, name.toLowerCase(), description, List.of(tags), "", "1.0.0", rank, 0,
                List.of(), PublisherRank.ADMIN);
    }

    private static final GalleryEntry FIRESTONE = bot("Firestone", "LiQiyeDev", PublisherRank.ADMIN,
            "Idle game farming", "idle");
    private static final GalleryEntry TAVERN = bot("Tavern", "someone", PublisherRank.COMMUNITY, "Plays the tavern");
    private static final GalleryEntry MINER = bot("Miner", "vetted", PublisherRank.TRUSTED, "Mines ore", "rpg");
    private static final List<GalleryEntry> ALL = List.of(TAVERN, MINER, FIRESTONE);
    private static final HiddenBots NONE = new HiddenBots(Set.of());

    private static List<String> names(List<GalleryEntry> entries) {
        return entries.stream().map(GalleryEntry::name).toList();
    }

    @Test
    void shownMatchesNameOwnerDescriptionAndTagsWithoutCase() {
        ShowCommunity all = new ShowCommunity(true);
        assertEquals(List.of("Firestone"), names(GetBots.shown(ALL, "fIRE", all, NONE, false)));
        assertEquals(List.of("Miner"), names(GetBots.shown(ALL, "VETTED", all, NONE, false)));
        assertEquals(List.of("Tavern"), names(GetBots.shown(ALL, "plays", all, NONE, false)));
        assertEquals(List.of("Miner"), names(GetBots.shown(ALL, "RPG", all, NONE, false)));
        assertEquals(List.of("Firestone", "Miner", "Tavern"), names(GetBots.shown(ALL, "", all, NONE, false)),
                "official, trusted, then community");
    }

    @Test
    void shownLeavesCommunityOutWhileItIsOff() {
        assertEquals(List.of("Firestone", "Miner"), names(GetBots.shown(ALL, "", new ShowCommunity(false), NONE, false)));
    }

    @Test
    void shownLeavesHiddenOutUnlessAsked() {
        HiddenBots hidden = NONE.with(MINER, true);
        ShowCommunity all = new ShowCommunity(true);
        assertEquals(List.of("Firestone", "Tavern"), names(GetBots.shown(ALL, "", all, hidden, false)));
        assertEquals(List.of("Firestone", "Miner", "Tavern"), names(GetBots.shown(ALL, "", all, hidden, true)));
    }
}
