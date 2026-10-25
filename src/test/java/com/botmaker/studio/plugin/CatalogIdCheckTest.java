package com.botmaker.studio.plugin;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublisherRank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                asked -> true, jar -> true, jar -> true,
                (jar, row, version) -> ReleasePins.Verdict.APPROVED);
    }

    @Test
    void aRunCopysPackedJarIsNamedByItsBotJsonLine() {
        // A run copy's lib/ jar says nothing by its path; its bot.json line names it (2026-10-25: "<tag> lib").
        Path packed = Path.of("/home/u/.local/share/botmaker/bots/bob/farm/v1.0.0/lib/farm-v1.0.0.jar");
        BotJson.Lib line = new BotJson.Lib("lib/farm-v1.0.0.jar", "com.github.bob:farm:v1.0.0", "ab".repeat(32));
        PluginGate gate = new PluginGate(() -> LISTED, REPO, () -> "", () -> CommunityConsent.of(""), kept -> {},
                asked -> true, jar -> true, jar -> true, (jar, row, version) -> ReleasePins.Verdict.APPROVED,
                jar -> jar.equals(packed) ? Optional.of(line) : Optional.empty());
        assertEquals("com.github.bob:farm", gate.coordinateOf(packed));
        assertEquals("v1.0.0", gate.versionOf(packed));
        assertEquals("farm v1.0.0", gate.describe(packed.toString()));
        assertEquals("com.github.bob:farm", gate.coordinateOf(FARM_JAR), "a repository jar as before");
        assertEquals("", gate.coordinateOf(Path.of("/elsewhere/farm.jar")));
    }

    @Test
    void aPackedJarHasItsClaimedRowOnlyWithThePinnedBytes(@TempDir Path dir) throws Exception {
        // bot.json is the bot author's word: a jar claiming a row a yes was given to must be that row's jar.
        Path jar = Files.write(dir.resolve("farm-v1.0.0.jar"), new byte[]{1, 2, 3});
        String sum = ReleasePins.sha256(jar).orElseThrow();
        BotJson.Lib line = new BotJson.Lib("lib/farm-v1.0.0.jar", "com.github.bob:farm:v1.0.0", sum);
        CatalogIndex pinned = listing(sum);
        PluginGate gate = new PluginGate(() -> pinned, REPO, () -> "", () -> CommunityConsent.of(""), kept -> {},
                asked -> true, path -> true, path -> true, (path, row, version) -> ReleasePins.Verdict.APPROVED,
                path -> path.equals(jar) ? Optional.of(line) : Optional.empty());
        assertEquals("farm", gate.rowOf(pinned, jar).orElseThrow().id(), "the catalog's own jar");
        assertTrue(gate.rowOf(listing("cd".repeat(32)), jar).isEmpty(), "another jar under that coordinate");
        assertTrue(gate.rowOf(LISTED, jar).isEmpty(), "a release the catalog pinned no jar for");

        // Agreed to once as the community row: a packed jar that only claims it is asked about again.
        CatalogIndex other = listing("cd".repeat(32));
        List<String> asked = new ArrayList<>();
        PluginGate agreed = new PluginGate(() -> other, REPO, () -> "",
                () -> CommunityConsent.of("").with("bob/farm#farm"), kept -> {},
                question -> asked.add(question.coordinate()), path -> true, path -> true,
                (path, row, version) -> ReleasePins.Verdict.APPROVED,
                path -> path.equals(jar) ? Optional.of(line) : Optional.empty());
        agreed.admit(other, List.of(jar.toString()), List.of());
        assertEquals(List.of(""), asked, "asked, as an unlisted plugin");
    }

    /** The farm row with its v1.0.0 release pinning {@code jarSha}. */
    private static CatalogIndex listing(String jarSha) {
        return new CatalogIndex(List.of(new PluginRegistry.Plugin("farm", "Farm", "com.github.bob:farm", "bob/farm",
                "", List.of(), "", List.of(), "", "v1.0.0", PublisherRank.COMMUNITY, 0,
                List.of(new Catalog.Plugin.Release("v1.0.0", "abc", jarSha, "ef".repeat(32), "", List.of(),
                        List.of())))), List.of());
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
