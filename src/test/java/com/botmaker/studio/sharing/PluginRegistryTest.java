package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of Manage Plugins that has no JavaFX in it: what a catalog row means, and whether a plugin is already
 * on this project.
 *
 * <p>The dialog is a browser and an Install button; everything it decides is here, so the decisions are
 * testable headlessly and the same rule as {@code BlockTree} applies — the model is pure, the view is not.
 */
class PluginRegistryTest {

    private static final String CATALOG = """
            {"schemaVersion": 3, "plugins": [ {
              "id" : "com.example.discord",
              "name" : "Discord Notifier",
              "repo" : "someone/botmaker-discord-plugin",
              "tag" : "v0.1.0",
              "rank" : "community",
              "coordinate" : "com.github.someone:botmaker-discord-plugin",
              "description" : "Sends a message when a bot finishes",
              "tags" : [ "notifications" ],
              "minContractVersion" : "v0.11.0"
            } ] }
            """;

    private static PluginRegistry.Plugin discord() {
        return CatalogIndex.parse(CATALOG).plugins().getFirst();
    }

    @Test
    void a_row_reads_back_whole_and_installs_its_tag() {
        PluginRegistry.Plugin plugin = discord();

        assertEquals("com.example.discord", plugin.id());
        assertEquals("com.github.someone", plugin.groupId());
        assertEquals("botmaker-discord-plugin", plugin.artifactId());
        assertEquals("v0.1.0", plugin.tag());
        assertEquals("v0.1.0", plugin.version(), "a pin is the tag name, never a newer release");
        assertEquals(PublisherRank.COMMUNITY, plugin.rank());
        assertEquals("https://github.com/someone/botmaker-discord-plugin", plugin.htmlUrl());
    }

    @Test
    void a_dev_build_keeps_its_own_version_over_the_tag() {
        assertEquals("1.6.0-SNAPSHOT", discord().withVersion("1.6.0-SNAPSHOT").version());
        assertEquals("v0.1.0", discord().withVersion("1.6.0-SNAPSHOT").tag());
    }

    /**
     * Installed is decided by coordinate and never by version — a plugin pinned to an older version is
     * still installed, and offering to install it again would be a lie.
     */
    @Test
    void installed_is_by_coordinate_not_by_version() {
        PluginRegistry.Plugin plugin = discord();
        List<UserLibrary> older = List.of(
                new UserLibrary("com.github.someone", "botmaker-discord-plugin", "v0.0.9"));

        assertTrue(plugin.isInstalledIn(older));
        assertFalse(plugin.isInstalledIn(List.of(new UserLibrary("com.github.someone", "something-else", "1"))));
        assertFalse(plugin.isInstalledIn(List.of()));
    }

    /** BotMaker's former groupId is the same plugin: offering it again under the new one declared it twice. */
    @Test
    void installed_counts_botmakers_former_group_id() {
        PluginRegistry.Plugin sdk = new PluginRegistry.Plugin("com.botmaker.sdk", "SDK",
                "com.github.BotMakerDev:botmaker-sdk", "", "", List.of(), "", List.of(), "1.3.0");

        assertTrue(sdk.isInstalledIn(List.of(new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "v1.2.3"))));
        assertFalse(sdk.isInstalledIn(List.of(new UserLibrary("com.github.someone", "botmaker-sdk", "1"))));
    }

    @Test
    void a_row_with_no_coordinate_is_not_installable() {
        PluginRegistry.Plugin plugin = CatalogIndex.parse(
                "{\"plugins\":[{\"id\":\"a.b\",\"repo\":\"o/a\",\"tag\":\"v1\"}]}").plugins().getFirst();

        assertFalse(plugin.isInstallable());
        assertEquals("a", plugin.name(), "no name: the repository's");
    }

    @Test
    void search_matches_name_id_description_and_tags() {
        PluginRegistry.Plugin plugin = discord();

        assertTrue(plugin.matches(""));
        assertTrue(plugin.matches(null));
        assertTrue(plugin.matches("discord"));
        assertTrue(plugin.matches("NOTIFICATIONS"));
        assertTrue(plugin.matches("finishes"));
        assertFalse(plugin.matches("telegram"));
    }
}
