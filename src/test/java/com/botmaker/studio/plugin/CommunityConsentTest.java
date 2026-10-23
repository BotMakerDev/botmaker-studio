package com.botmaker.studio.plugin;

import com.botmaker.plugin.host.PluginLoader;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is asked before a plugin first loads on this computer, and what a yes or a no does (2026-10-23). */
class CommunityConsentTest {

    private static final Path REPO = Path.of("/home/u/.m2/repository");
    private static final String SDK_JAR = REPO.resolve(
            "com/github/BotMakerDev/botmaker-sdk/v1.0.0/botmaker-sdk-v1.0.0.jar").toString();
    private static final String SDK_ELSEWHERE_JAR = REPO.resolve(
            "com/github/BotMakerDev/botmaker-sdk/9.9.9/botmaker-sdk-9.9.9.jar").toString();
    private static final String COMMUNITY_JAR = REPO.resolve(
            "com/github/bob/farm-plugin/v1.0.0/farm-plugin-v1.0.0.jar").toString();
    private static final String LIBRARY_JAR = REPO.resolve("org/slf4j/slf4j-api/2.0.0/slf4j-api-2.0.0.jar").toString();

    private static PluginRegistry.Plugin row(String id, String coordinate, String repo, PublisherRank rank) {
        return new PluginRegistry.Plugin(id, id, coordinate, repo, "", List.of(), "", List.of(), "", "v1.0.0",
                rank, 0);
    }

    private static final PluginRegistry.Plugin SDK = row("com.botmaker.sdk", "com.github.BotMakerDev:botmaker-sdk",
            "BotMakerDev/botmaker-sdk", PublisherRank.ADMIN);
    private static final PluginRegistry.Plugin FARM = row("farm", "com.github.bob:farm-plugin", "bob/farm-plugin",
            PublisherRank.COMMUNITY);
    private static final CatalogIndex LISTED = new CatalogIndex(List.of(SDK, FARM), List.of());

    @Test
    void officialAndTrustedRowsAtTheirListedTagAreNotAsked() {
        assertFalse(CommunityConsent.needsAsking(SDK.coordinate(), "v1.0.0", Optional.of(SDK), true, "", false));
        PluginRegistry.Plugin trusted = row("t", "com.github.carol:t", "carol/t", PublisherRank.TRUSTED);
        assertFalse(CommunityConsent.needsAsking(trusted.coordinate(), "v1.0.0", Optional.of(trusted), true, "",
                false));
    }

    /**
     * The user's call (2026-10-23): a rank vouches for the listed tag only. Any pom may name its own repository
     * and serve another version under an official coordinate.
     */
    @Test
    void anotherVersionOfAnOfficialRowIsAskedOncePerVersion() {
        assertTrue(CommunityConsent.needsAsking(SDK.coordinate(), "9.9.9", Optional.of(SDK), true, "", false));
        assertTrue(CommunityConsent.needsAsking(SDK.coordinate(), "v0.9.0", Optional.of(SDK), true, "", false));
        assertEquals("botmakerdev/botmaker-sdk#com.botmaker.sdk@9.9.9",
                CommunityConsent.key(SDK.coordinate(), "9.9.9", Optional.of(SDK)));

        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), new AtomicReference<>(), asked, false);
        assertEquals(List.of(SDK_JAR), gate.admit(LISTED, List.of(SDK_JAR, SDK_ELSEWHERE_JAR), List.of()).loadable());
        assertTrue(asked.getFirst().question().contains("not the release the catalog lists (v1.0.0)"),
                asked.getFirst().question());
    }

    @Test
    void communityRowsAndUnlistedCoordinatesAreAsked() {
        assertTrue(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "alice",
                false));
        assertTrue(CommunityConsent.needsAsking("com.github.eve:x", "1", Optional.empty(), true, "alice", false));
        assertTrue(CommunityConsent.needsAsking("", "", Optional.empty(), true, "", false), "a jar nothing names");
    }

    /** Review Focus 1: offline on a first start, no catalog kept, the SDK and basics load with no prompt. */
    @Test
    void withNoCatalogAtAllBotMakersOwnGroupIsNotAsked() {
        assertFalse(CommunityConsent.needsAsking("com.github.BotMakerDev:botmaker-sdk", "v1.5.0", Optional.empty(),
                false, "", false));
        assertFalse(CommunityConsent.needsAsking("com.github.LiQiyeDev:botmaker-sdk", "1.4.2", Optional.empty(),
                false, "", false));
        assertTrue(CommunityConsent.needsAsking("com.github.eve:x", "1", Optional.empty(), false, "", false));
        assertTrue(CommunityConsent.needsAsking("com.github.BotMakerDev:not-listed", "1", Optional.empty(), true, "",
                false), "once a catalog is read, an unlisted coordinate is asked whatever its group");
    }

    /** Review Focus 5: a dev build and the signed-in user's own plugin are never asked about. */
    @Test
    void devBuildsAndTheUsersOwnPluginsAreNotAsked() {
        assertFalse(CommunityConsent.needsAsking(FARM.coordinate(), "2-SNAPSHOT", Optional.of(FARM), true, "", true));
        assertFalse(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "Bob", false));
        assertFalse(CommunityConsent.needsAsking("com.github.alice:mine", "1", Optional.empty(), true, "alice",
                false));
        assertTrue(CommunityConsent.needsAsking("com.github.alice:mine", "1", Optional.empty(), true, "", false),
                "signed out, nobody's plugin is the user's");
    }

    @Test
    void aYesIsKeptByEntryAndRoundTrips() {
        assertEquals("bob/farm-plugin#farm", CommunityConsent.key(FARM.coordinate(), "v2.0.0", Optional.of(FARM)));
        assertEquals("com.github.eve:x", CommunityConsent.key("com.github.eve:x", "1", Optional.empty()));
        CommunityConsent agreed = CommunityConsent.of("").with("bob/farm-plugin#farm").with("com.github.eve:x");
        assertEquals(agreed, CommunityConsent.of(String.join("\n", agreed.allowed())));
        assertTrue(agreed.allows("bob/farm-plugin#farm"));
    }

    private static PluginGate gate(CatalogIndex listed, String login, CommunityConsent start,
                                   AtomicReference<CommunityConsent> kept, List<PluginGate.Asked> asked,
                                   boolean answer) {
        kept.set(start);
        return new PluginGate(() -> listed, REPO, () -> login, kept::get, kept::set, question -> {
            asked.add(question);
            return answer;
        }, jar -> !jar.toString().contains("slf4j"), jar -> !jar.toString().contains("downloaded"));
    }

    @Test
    void aCommunityPluginIsAskedAboutOnceAndAYesIsKept() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>();
        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), kept, asked, true);

        PluginGate.Admitted first = gate.admit(LISTED, List.of(SDK_JAR, COMMUNITY_JAR, LIBRARY_JAR), List.of());
        assertEquals(List.of(SDK_JAR, COMMUNITY_JAR, LIBRARY_JAR), first.loadable());
        assertEquals(1, asked.size(), "only the community plugin: the SDK is official, slf4j declares no plugin");
        assertEquals(Optional.of(FARM), asked.getFirst().row());
        assertTrue(asked.getFirst().question().contains("by bob"), asked.getFirst().question());
        assertEquals(Set.of("bob/farm-plugin#farm"), kept.get().allowed());

        gate.admit(LISTED, List.of(COMMUNITY_JAR), List.of());
        assertEquals(1, asked.size(), "a kept yes is not asked again");
    }

    @Test
    void aNoLeavesThePluginOffTheLoaderAndIsAskedAgain() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>();
        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), kept, asked, false);

        PluginGate.Admitted admitted = gate.admit(LISTED, List.of(SDK_JAR, COMMUNITY_JAR), List.of());
        assertEquals(List.of(SDK_JAR), admitted.loadable());
        PluginLoader.PluginFailure refused = admitted.refused().getFirst();
        assertEquals("farm-plugin v1.0.0 — " + PluginGate.DECLINED, refused.describe());
        assertTrue(kept.get().allowed().isEmpty());

        gate.admit(LISTED, List.of(COMMUNITY_JAR), List.of());
        assertEquals(2, asked.size(), "a no is not remembered");
    }

    @Test
    void offlineOnAFirstStartTheSdkLoadsUnasked() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>();
        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(CatalogIndex.EMPTY, "", CommunityConsent.of(""), kept, asked, false);

        assertEquals(List.of(SDK_JAR), gate.admit(CatalogIndex.EMPTY, List.of(SDK_JAR), List.of()).loadable());
        assertTrue(asked.isEmpty());
    }

    @Test
    void aDevBuildIsNotAskedAbout() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>();
        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), kept, asked, false);

        assertEquals(List.of(COMMUNITY_JAR), gate.admit(LISTED, List.of(COMMUNITY_JAR), List.of(COMMUNITY_JAR))
                .loadable());
        assertTrue(asked.isEmpty());
    }

    /**
     * Dev mode is read from the project's own files, which a cloned bot may bring with its own repository: a
     * SNAPSHOT this computer downloaded rather than installed is asked about like any community plugin.
     */
    @Test
    void aDownloadedSnapshotInDevModeIsStillAsked() {
        String downloaded = REPO.resolve("com/github/eve/downloaded/1.0-SNAPSHOT/downloaded-1.0-SNAPSHOT.jar")
                .toString();
        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), new AtomicReference<>(), asked, false);

        assertTrue(gate.admit(LISTED, List.of(downloaded), List.of(downloaded)).loadable().isEmpty());
        assertEquals(1, asked.size());
    }

    /** Maven's own record of where a jar came from: {@code <file>>=} for {@code mvn install}. */
    @Test
    void onlyAJarMavenInstalledHereCountsAsBuiltHere(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("local/x-1.0-SNAPSHOT.jar");
        Path fetched = dir.resolve("fetched/x-1.0-SNAPSHOT.jar");
        Files.createDirectories(local.getParent());
        Files.createDirectories(fetched.getParent());
        Files.writeString(local.resolveSibling("_remote.repositories"), "#NOTE\nx-1.0-SNAPSHOT.jar>=\n");
        Files.writeString(fetched.resolveSibling("_remote.repositories"), "x-1.0-SNAPSHOT.jar>evil-repo=\n");

        assertTrue(PluginGate.installedHere(local));
        assertFalse(PluginGate.installedHere(fetched));
        assertFalse(PluginGate.installedHere(dir.resolve("x.jar")), "no record is not a local install");
    }

    @Test
    void anAskerThatThrowsIsANo() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>(CommunityConsent.of(""));
        PluginGate gate = new PluginGate(() -> LISTED, REPO, () -> "", kept::get, kept::set, question -> {
            throw new IllegalStateException("no toolkit");
        }, jar -> true, jar -> true);

        assertTrue(gate.admit(LISTED, List.of(COMMUNITY_JAR), List.of()).loadable().isEmpty());
    }

    @Test
    void aJarsCoordinateIsReadOffTheRepositoryLayout() {
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), new AtomicReference<>(), new ArrayList<>(), true);
        assertEquals("com.github.BotMakerDev:botmaker-sdk", gate.coordinateOf(Path.of(SDK_JAR)));
        assertEquals("", gate.coordinateOf(Path.of("/opt/plugins/x.jar")));
        assertEquals(Optional.of(SDK), PluginGate.rowFor(LISTED, "com.github.LiQiyeDev:botmaker-sdk"),
                "BotMaker's former groupId names the same entry");
    }
}
