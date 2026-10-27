package com.botmaker.studio.plugin;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublisherRank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which catalog row a jar is (2026-10-25): a repository jar by its path, a run copy's packed jar by its
 * {@code bot.json} line, and that line only when the jar's bytes are the ones the catalog pinned. Until
 * 2026-10-27 this file also checked that a catalog plugin said its entry's id; a plugin has had no id since.
 */
class PackedJarRowTest {

    private static final Path REPO = Path.of("/home/u/.m2/repository");
    private static final Path FARM_JAR = REPO.resolve("com/github/bob/farm/v1.0.0/farm-v1.0.0.jar");

    private static final CatalogIndex LISTED = new CatalogIndex(List.of(new PluginRegistry.Plugin("Farm",
            "com.github.bob:farm", "bob/farm", "", List.of(), "", List.of(), "", "v1.0.0", PublisherRank.COMMUNITY,
            0)), List.of());

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
        assertEquals("bob/farm", gate.rowOf(pinned, jar).orElseThrow().repo(), "the catalog's own jar");
        assertTrue(gate.rowOf(listing("cd".repeat(32)), jar).isEmpty(), "another jar under that coordinate");
        assertTrue(gate.rowOf(LISTED, jar).isEmpty(), "a release the catalog pinned no jar for");

        // Agreed to once as the community row: a packed jar that only claims it is asked about again.
        CatalogIndex other = listing("cd".repeat(32));
        List<String> asked = new ArrayList<>();
        PluginGate agreed = new PluginGate(() -> other, REPO, () -> "",
                () -> CommunityConsent.of("").with("bob/farm"), kept -> {},
                question -> asked.add(question.coordinate()), path -> true, path -> true,
                (path, row, version) -> ReleasePins.Verdict.APPROVED,
                path -> path.equals(jar) ? Optional.of(line) : Optional.empty());
        agreed.admit(other, List.of(jar.toString()), List.of());
        assertEquals(List.of(""), asked, "asked, as an unlisted plugin");
    }

    /** The farm row with its v1.0.0 release pinning {@code jarSha}. */
    private static CatalogIndex listing(String jarSha) {
        return new CatalogIndex(List.of(new PluginRegistry.Plugin("Farm", "com.github.bob:farm", "bob/farm",
                "", List.of(), "", List.of(), "", "v1.0.0", PublisherRank.COMMUNITY, 0,
                List.of(new Catalog.Plugin.Release("v1.0.0", "abc", jarSha, "ef".repeat(32), "", List.of(),
                        List.of())))), List.of());
    }
}
