package com.botmaker.studio.sharing;

import com.botmaker.studio.services.LocalBuilds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What Browse offers a project in dev mode: the registry, with this computer's builds over it. */
class PluginCatalogTest {

    private static PluginRegistry.Plugin published(String id, String coordinate, String version) {
        return new PluginRegistry.Plugin(id, id, coordinate, "", "", List.of(), "",
                List.of("g:companion:1"), version, "");
    }

    private static LocalBuilds.Build build(String artifactId, String version) {
        return new LocalBuilds.Build("g", artifactId, version, Instant.EPOCH);
    }

    @Test
    void aLocalBuildReplacesThePublishedVersionAndKeepsTheEntry() {
        List<PluginRegistry.Plugin> rows = PluginCatalog.withLocalBuilds(
                List.of(published("sdk", "g:sdk", "2.0.0")), List.of(build("sdk", "2.0.1-SNAPSHOT")));

        assertEquals(1, rows.size(), "one row per coordinate, never two versions");
        assertEquals("2.0.1-SNAPSHOT", rows.getFirst().verifiedVersion());
        assertEquals(List.of("g:companion:1"), rows.getFirst().editorDependencies());
    }

    @Test
    void anUnpublishedLocalBuildLeadsTheList() {
        List<PluginRegistry.Plugin> rows = PluginCatalog.withLocalBuilds(
                List.of(published("sdk", "g:sdk", "2.0.0")), List.of(build("mine", "0.1.0-SNAPSHOT")));

        assertEquals("g:mine", rows.getFirst().coordinate());
        assertEquals(PluginCatalog.LOCAL_BUILD_DESCRIPTION, rows.getFirst().description());
        assertEquals(2, rows.size());
    }

    @Test
    void unpublishedBuildsKeepTheirNewestFirstOrder() {
        List<PluginRegistry.Plugin> rows = PluginCatalog.withLocalBuilds(List.of(published("sdk", "g:sdk", "2.0.0")),
                List.of(build("newest", "0.2.0-SNAPSHOT"), build("older", "0.1.0-SNAPSHOT")));

        assertEquals(List.of("g:newest", "g:older", "g:sdk"), rows.stream().map(PluginRegistry.Plugin::coordinate).toList());
    }

    @Test
    void noLocalBuildIsTheRegistryAlone() {
        List<PluginRegistry.Plugin> published = List.of(published("sdk", "g:sdk", "2.0.0"));

        assertEquals(published, PluginCatalog.withLocalBuilds(published, List.of()));
    }
}
