package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublisherRank;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A plugin loaded from a catalog coordinate binds only under the id its entry lists (2026-10-23): the catalog's
 * check reads the id by running the plugin, and a plugin that exits the JVM there is never compared.
 */
class CatalogIdCheckTest {

    private static final Path REPO = Path.of("/home/u/.m2/repository");
    private static final Path FARM_JAR = REPO.resolve("com/github/bob/farm/v1.0.0/farm-v1.0.0.jar");
    private static final Path OTHER_JAR = REPO.resolve("com/github/eve/other/v2.0.0/other-v2.0.0.jar");
    private static final Path DEV_JAR = REPO.resolve("com/github/bob/farm/1.1.0-SNAPSHOT/farm-1.1.0-SNAPSHOT.jar");

    private static final CatalogIndex LISTED = new CatalogIndex(List.of(new PluginRegistry.Plugin("farm", "Farm",
            "com.github.bob:farm", "bob/farm", "", List.of(), "", List.of(), "", "v1.0.0", PublisherRank.COMMUNITY,
            0)), List.of());

    private static StudioPlugin plugin(String id) {
        return () -> id;
    }

    private static PluginGate gate() {
        return new PluginGate(() -> LISTED, REPO, () -> "", () -> CommunityConsent.of(""), kept -> {},
                asked -> true, jar -> true, jar -> true, (jar, row) -> true);
    }

    @Test
    void theEntrysOwnIdBinds() {
        StudioPlugin farm = plugin("farm");
        PluginGate.Checked checked = gate().checkIds(LISTED, List.of(farm), p -> Optional.of(FARM_JAR), List.of());
        assertEquals(List.of(farm), checked.bound());
        assertEquals(List.of(), checked.refused());
    }

    @Test
    void anotherIdAtTheEntrysCoordinateIsRefused() {
        StudioPlugin impostor = plugin("com.botmaker.sdk");
        PluginGate.Checked checked = gate().checkIds(LISTED, List.of(impostor), p -> Optional.of(FARM_JAR),
                List.of());
        assertEquals(List.of(), checked.bound());
        assertEquals("farm v1.0.0 — its id is com.botmaker.sdk, but the catalog lists this coordinate as farm",
                checked.refused().getFirst().describe());
    }

    @Test
    void devBuildsAndUnlistedCoordinatesAreNotCompared() {
        StudioPlugin dev = plugin("renamed");
        StudioPlugin unlisted = plugin("anything");
        Map<StudioPlugin, Path> jars = Map.of(dev, DEV_JAR, unlisted, OTHER_JAR);
        PluginGate.Checked checked = gate().checkIds(LISTED, List.of(dev, unlisted),
                p -> Optional.of(jars.get(p)), List.of(DEV_JAR.toString()));
        assertEquals(List.of(dev, unlisted), checked.bound());
    }

    @Test
    void aPluginFromNoJarIsNotCompared() {
        StudioPlugin bundled = plugin("bundled");
        assertEquals(List.of(bundled),
                gate().checkIds(LISTED, List.of(bundled), p -> Optional.empty(), List.of()).bound());
    }

    @Test
    void anIdThatThrowsIsRefusedAtACatalogCoordinate() {
        StudioPlugin broken = () -> {
            throw new IllegalStateException("boom");
        };
        PluginGate.Checked checked = gate().checkIds(LISTED, List.of(broken), p -> Optional.of(FARM_JAR), List.of());
        assertEquals("farm v1.0.0 — its id is unreadable, but the catalog lists this coordinate as farm",
                checked.refused().getFirst().describe());
    }
}
