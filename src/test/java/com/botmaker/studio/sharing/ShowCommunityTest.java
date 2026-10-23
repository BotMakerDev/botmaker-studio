package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** <i>Show community bots and plugins</i>, off until turned on (2026-10-23). */
class ShowCommunityTest {

    private static PluginRegistry.Plugin plugin(String id, PublisherRank rank, int stars) {
        return new PluginRegistry.Plugin(id, id, "g:" + id, "o/" + id, "", List.of(), "", List.of(), "", "v1", rank,
                stars);
    }

    private static GalleryEntry bot(String name, PublisherRank rank, PublisherRank pluginsRank) {
        return new GalleryEntry(name, "o", name, "", List.of(), "", "v1", rank, 0, List.of(), pluginsRank);
    }

    @Test
    void offShowsOfficialAndTrustedPluginsRanked() {
        PluginRegistry.Plugin community = plugin("c", PublisherRank.COMMUNITY, 50);
        PluginRegistry.Plugin trusted = plugin("t", PublisherRank.TRUSTED, 1);
        PluginRegistry.Plugin official = plugin("a", PublisherRank.ADMIN, 0);
        PluginRegistry.Plugin starred = plugin("s", PublisherRank.TRUSTED, 7);
        List<PluginRegistry.Plugin> rows = List.of(community, trusted, official, starred);

        assertEquals(List.of(official, starred, trusted), new ShowCommunity(false).plugins(rows));
        assertEquals(List.of(official, starred, trusted, community), new ShowCommunity(true).plugins(rows));
    }

    @Test
    void offHidesABotWhosePluginIsCommunity() {
        GalleryEntry trusted = bot("t", PublisherRank.TRUSTED, PublisherRank.ADMIN);
        GalleryEntry usesCommunity = bot("u", PublisherRank.TRUSTED, PublisherRank.COMMUNITY);
        GalleryEntry community = bot("c", PublisherRank.COMMUNITY, PublisherRank.ADMIN);

        assertEquals(List.of(trusted), new ShowCommunity(false).bots(List.of(community, usesCommunity, trusted)));
        assertEquals(List.of(trusted, community, usesCommunity),
                new ShowCommunity(true).bots(List.of(usesCommunity, community, trusted)));
    }
}
