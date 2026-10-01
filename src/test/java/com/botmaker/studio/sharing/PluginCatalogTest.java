package com.botmaker.studio.sharing;

import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The one list Browse and New Project both offer: the registry, with local builds taking precedence. */
class PluginCatalogTest {

    private static PluginRegistry.Plugin published(String id, String coordinate, String version) {
        return new PluginRegistry.Plugin(id, id, coordinate, "", "", List.of(), "",
                List.of("g:companion:1"), version, "");
    }

    @Test
    void aLocalBuildReplacesThePublishedVersionAndKeepsTheEntry() {
        PluginCatalog.Rows rows = PluginCatalog.merge(
                List.of(published("sdk", "g:sdk", "2.0.0")),
                List.of(new MavenService.LocalPluginBuild("g", "sdk", "0.0.0-SNAPSHOT")));

        assertEquals(1, rows.plugins().size(), "one row per coordinate, never two versions");
        assertEquals("0.0.0-SNAPSHOT", rows.plugins().getFirst().verifiedVersion());
        assertEquals(List.of("g:companion:1"), rows.plugins().getFirst().editorDependencies());
        assertEquals(Set.of("g:sdk"), rows.localCoordinates());
    }

    @Test
    void anUnpublishedLocalBuildLeadsTheList() {
        PluginCatalog.Rows rows = PluginCatalog.merge(
                List.of(published("sdk", "g:sdk", "2.0.0")),
                List.of(new MavenService.LocalPluginBuild("g", "mine", "1.0-SNAPSHOT")));

        assertEquals("g:mine", rows.plugins().getFirst().coordinate());
        assertEquals(2, rows.plugins().size());
    }

    @Test
    void aReleasedStudioShowsTheRegistryAlone() {
        PluginCatalog.Rows rows = PluginCatalog.merge(List.of(published("sdk", "g:sdk", "2.0.0")), List.of());

        assertEquals(List.of(published("sdk", "g:sdk", "2.0.0")), rows.plugins());
        assertEquals(Set.of(), rows.localCoordinates());
    }
}
