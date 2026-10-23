package com.botmaker.studio.plugin;

import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublisherRank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    private static final BooleanSupplier RELEASE = () -> true;
    private static final BooleanSupplier NOT_RELEASE = () -> false;

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
        assertFalse(CommunityConsent.needsAsking(SDK.coordinate(), "v1.0.0", Optional.of(SDK), true, "", false,
                RELEASE));
        PluginRegistry.Plugin trusted = row("t", "com.github.carol:t", "carol/t", PublisherRank.TRUSTED);
        assertFalse(CommunityConsent.needsAsking(trusted.coordinate(), "v1.0.0", Optional.of(trusted), true, "",
                false, RELEASE));
    }

    /**
     * The user's call (2026-10-23): a rank vouches for the listed tag only. Any pom may name its own repository
     * and serve another version under an official coordinate.
     */
    @Test
    void anotherVersionOfAnOfficialRowIsAskedOncePerVersion() {
        assertTrue(CommunityConsent.needsAsking(SDK.coordinate(), "9.9.9", Optional.of(SDK), true, "", false,
                RELEASE));
        assertTrue(CommunityConsent.needsAsking(SDK.coordinate(), "v0.9.0", Optional.of(SDK), true, "", false,
                RELEASE));
        assertEquals("botmakerdev/botmaker-sdk#com.botmaker.sdk@9.9.9",
                CommunityConsent.key(SDK.coordinate(), "9.9.9", Optional.of(SDK)));

        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = gate(LISTED, "", CommunityConsent.of(""), new AtomicReference<>(), asked, false);
        assertEquals(List.of(SDK_JAR), gate.admit(LISTED, List.of(SDK_JAR, SDK_ELSEWHERE_JAR), List.of()).loadable());
        assertTrue(asked.getFirst().question().contains("not the release the catalog lists (v1.0.0)"),
                asked.getFirst().question());
    }

    /** Phase 7b: another repository's jar at the listed tag is not the release, whatever its version says. */
    @Test
    void aJarAtTheListedTagThatIsNotJitPacksIsAsked() {
        assertTrue(CommunityConsent.needsAsking(SDK.coordinate(), "v1.0.0", Optional.of(SDK), true, "", false,
                NOT_RELEASE));

        List<PluginGate.Asked> asked = new ArrayList<>();
        PluginGate gate = new PluginGate(() -> LISTED, REPO, () -> "", () -> CommunityConsent.of(""), kept -> {},
                question -> {
                    asked.add(question);
                    return false;
                }, jar -> true, jar -> false, (jar, row) -> false);
        assertTrue(gate.admit(LISTED, List.of(SDK_JAR), List.of()).loadable().isEmpty());
        assertEquals(1, asked.size());
    }

    @Test
    void theChecksumIsAskedOnlyOfARowThatVouches() {
        BooleanSupplier never = () -> {
            throw new AssertionError("no checksum is needed to ask");
        };
        assertTrue(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "", false,
                never));
        assertTrue(CommunityConsent.needsAsking(SDK.coordinate(), "9.9.9", Optional.of(SDK), true, "", false,
                never));
        assertFalse(CommunityConsent.needsAsking(SDK.coordinate(), "9.9.9", Optional.of(SDK), true, "", true,
                never), "a dev build is not compared");
    }

    @Test
    void communityRowsAndUnlistedCoordinatesAreAsked() {
        assertTrue(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "alice",
                false, RELEASE));
        assertTrue(CommunityConsent.needsAsking("com.github.eve:x", "1", Optional.empty(), true, "alice", false,
                RELEASE));
        assertTrue(CommunityConsent.needsAsking("", "", Optional.empty(), true, "", false, RELEASE),
                "a jar nothing names");
    }

    /** Review Focus 1: offline on a first start, no catalog kept, the SDK and basics load with no prompt. */
    @Test
    void withNoCatalogAtAllBotMakersOwnGroupIsNotAsked() {
        assertFalse(CommunityConsent.needsAsking("com.github.BotMakerDev:botmaker-sdk", "v1.5.0", Optional.empty(),
                false, "", false, NOT_RELEASE));
        assertFalse(CommunityConsent.needsAsking("com.github.LiQiyeDev:botmaker-sdk", "1.4.2", Optional.empty(),
                false, "", false, NOT_RELEASE));
        assertTrue(CommunityConsent.needsAsking("com.github.eve:x", "1", Optional.empty(), false, "", false,
                RELEASE));
        assertTrue(CommunityConsent.needsAsking("com.github.BotMakerDev:not-listed", "1", Optional.empty(), true, "",
                false, RELEASE), "once a catalog is read, an unlisted coordinate is asked whatever its group");
    }

    /** Review Focus 5: a dev build, and the signed-in user's own row at its tag, are never asked about. */
    @Test
    void devBuildsAndTheUsersOwnRowAtItsTagAreNotAsked() {
        assertFalse(CommunityConsent.needsAsking(FARM.coordinate(), "2-SNAPSHOT", Optional.of(FARM), true, "", true,
                NOT_RELEASE));
        assertFalse(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "Bob", false,
                RELEASE));
        assertTrue(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "", false,
                RELEASE), "signed out, nobody's plugin is the user's");
    }

    /**
     * Phase 7b: any repository can serve {@code com.github.<login>}, so the user's groupId vouches for nothing —
     * only their own catalog row, at its tag, as JitPack built it.
     */
    @Test
    void theUsersOwnGroupIdAloneIsAsked() {
        assertTrue(CommunityConsent.needsAsking("com.github.alice:mine", "1", Optional.empty(), true, "alice",
                false, RELEASE));
        assertTrue(CommunityConsent.needsAsking(FARM.coordinate(), "v2.0.0", Optional.of(FARM), true, "bob", false,
                RELEASE), "another version of the user's own row");
        assertTrue(CommunityConsent.needsAsking(FARM.coordinate(), "v1.0.0", Optional.of(FARM), true, "bob", false,
                NOT_RELEASE), "the user's own row at its tag, from another repository");
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
        }, jar -> !jar.toString().contains("slf4j"), jar -> !jar.toString().contains("downloaded"),
                (jar, row) -> true);
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

    /**
     * Maven's own records of where a jar came from: {@code <file>>=} and {@code maven-metadata-local.xml} for
     * {@code mvn install}. Phase 7b: an empty repository id writes the same line, but its own metadata file.
     */
    @Test
    void onlyAJarMavenInstalledHereCountsAsBuiltHere(@TempDir Path dir) throws Exception {
        Path local = snapshot(dir, "local", "x-1.0-SNAPSHOT.jar>=", "maven-metadata-local.xml");
        Path fetched = snapshot(dir, "fetched", "x-1.0-SNAPSHOT.jar>evil-repo=", "maven-metadata-evil-repo.xml");
        Path emptyId = snapshot(dir, "empty-id", "x-1.0-SNAPSHOT.jar>=", "maven-metadata-.xml");
        Path both = snapshot(dir, "both", "x-1.0-SNAPSHOT.jar>=", "maven-metadata-local.xml", "maven-metadata-.xml");
        Path localId = snapshot(dir, "local-id", "x-1.0-SNAPSHOT.jar>local=", "maven-metadata-local.xml");
        Path noMetadata = snapshot(dir, "no-metadata", "x-1.0-SNAPSHOT.jar>=");

        assertTrue(PluginGate.installedHere(local));
        assertFalse(PluginGate.installedHere(fetched));
        assertFalse(PluginGate.installedHere(emptyId), "a repository with an empty id");
        assertFalse(PluginGate.installedHere(both), "installed, then overwritten from an empty-id repository");
        assertFalse(PluginGate.installedHere(localId), "a repository with the id local");
        assertFalse(PluginGate.installedHere(noMetadata));
        assertFalse(PluginGate.installedHere(dir.resolve("x.jar")), "no record is not a local install");
    }

    private static Path snapshot(Path dir, String name, String remoteLine, String... metadata) throws Exception {
        Path jar = dir.resolve(name).resolve("x-1.0-SNAPSHOT.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar.resolveSibling("_remote.repositories"), "#NOTE\n" + remoteLine + "\n");
        for (String file : metadata) Files.writeString(jar.resolveSibling(file), "<metadata/>");
        return jar;
    }

    /** Phase 7b: a library with classes in a plugin's package could run as that plugin; it is left off. */
    @Test
    void aLibraryInAnAdmittedPluginsPackageIsLeftOff(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repository");
        Path sdk = jar(repo.resolve("com/github/BotMakerDev/botmaker-sdk/v1.0.0/botmaker-sdk-v1.0.0.jar"),
                "META-INF/services/com.botmaker.plugin.api.StudioPlugin", "com/botmaker/sdk/plugin/SdkPlugin.class");
        Path evil = jar(repo.resolve("com/github/eve/util/1.0/util-1.0.jar"),
                "META-INF/versions/17/com/botmaker/sdk/plugin/SdkPlugin.class", "com/eve/Util.class");
        Path plain = jar(repo.resolve("org/slf4j/slf4j-api/2.0.0/slf4j-api-2.0.0.jar"), "org/slf4j/Logger.class",
                "module-info.class");
        CatalogIndex listed = new CatalogIndex(List.of(SDK), List.of());
        PluginGate gate = new PluginGate(() -> listed, repo, () -> "", () -> CommunityConsent.of(""), kept -> {},
                question -> true, MavenService::declaresPlugin, jar -> false, (jar, row) -> true);

        PluginGate.Admitted admitted = gate.admit(listed, List.of(sdk.toString(), evil.toString(), plain.toString()),
                List.of());
        assertEquals(List.of(sdk.toString(), plain.toString()), admitted.loadable());
        assertEquals("util 1.0 — " + PluginGate.shadows("com.botmaker.sdk.plugin", sdk.toString()),
                admitted.refused().getFirst().describe());
        assertEquals(Set.of("com.botmaker.sdk.plugin"), PluginGate.packagesOf(sdk));
        assertEquals(Set.of("org.slf4j"), PluginGate.packagesOf(plain), "module-info is no package");
    }

    private static Path jar(Path path, String... entries) throws Exception {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.closeEntry();
            }
        }
        return path;
    }

    /** Phase 7b: JitPack's checksum, fetched once and remembered; offline and never checked is not the release. */
    @Test
    void aJarIsTheReleaseOnlyWhenItsChecksumIsJitPacks(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("real.jar");
        Path fake = dir.resolve("fake.jar");
        Files.writeString(real, "the release");
        Files.writeString(fake, "something else");
        String sum = ReleaseChecksums.sha1(real).orElseThrow();
        Map<String, String> kept = new HashMap<>();
        List<String> fetched = new ArrayList<>();
        AtomicReference<Boolean> online = new AtomicReference<>(true);
        ReleaseChecksums checksums = new ReleaseChecksums(url -> {
            fetched.add(url);
            return online.get() ? Optional.of(sum.toUpperCase(Locale.ROOT) + "  botmaker-sdk-v1.0.0.jar\n")
                    : Optional.empty();
        }, () -> kept, next -> {
            kept.clear();
            kept.putAll(next);
        });

        assertEquals("https://jitpack.io/com/github/BotMakerDev/botmaker-sdk/v1.0.0/botmaker-sdk-v1.0.0.jar.sha1",
                ReleaseChecksums.url(SDK));
        assertTrue(checksums.isRelease(real, SDK));
        assertEquals(Map.of("com.github.BotMakerDev:botmaker-sdk@v1.0.0", sum), kept);
        assertTrue(checksums.isRelease(real, SDK));
        assertEquals(1, fetched.size(), "a jar that matches the remembered checksum is not fetched again");
        assertFalse(checksums.isRelease(fake, SDK));
        assertEquals(2, fetched.size(), "a mismatch is compared once more with a fresh checksum");

        online.set(false);
        assertTrue(checksums.isRelease(real, SDK), "a tag checked once is checked offline");
        kept.clear();
        assertFalse(checksums.isRelease(real, SDK), "offline and never checked");
        assertFalse(checksums.isRelease(dir.resolve("missing.jar"), SDK));
        assertTrue(kept.isEmpty(), "nothing fetched, nothing remembered");
        assertEquals(Map.of("a@v1", "abc"), ReleaseChecksums.of("a@v1 abc\n\n"));
    }

    /** Review finding: JitPack rebuilds an evicted tag, so a remembered checksum may be stale. */
    @Test
    void aRebuiltReleaseReplacesTheRememberedChecksum(@TempDir Path dir) throws Exception {
        Path rebuilt = dir.resolve("rebuilt.jar");
        Files.writeString(rebuilt, "the rebuild");
        String sum = ReleaseChecksums.sha1(rebuilt).orElseThrow();
        Map<String, String> kept = new HashMap<>(Map.of(ReleaseChecksums.key(SDK), "0".repeat(40)));
        ReleaseChecksums checksums = new ReleaseChecksums(url -> Optional.of(sum + "\n"), () -> kept, next -> {
            kept.clear();
            kept.putAll(next);
        });

        assertTrue(checksums.isRelease(rebuilt, SDK));
        assertEquals(Map.of(ReleaseChecksums.key(SDK), sum), kept);
    }

    /** Review finding: one checksum per coordinate, so the preference never outgrows its 8192 characters. */
    @Test
    void anotherTagReplacesItsCoordinatesChecksum() {
        Map<String, String> sums = Map.of("com.github.a:p@v1", "1", "com.github.a:plugin@v1", "2");
        assertEquals(Map.of("com.github.a:p@v2", "3", "com.github.a:plugin@v1", "2"),
                ReleaseChecksums.with(sums, "com.github.a:p@v2", "3"));
    }

    @Test
    void onlyABodyThatStartsWithTheChecksumIsOne() {
        String sum = "921ae160788900cd0e31dc0f547b2de116f0b47d";
        assertEquals(Optional.of(sum), ReleaseChecksums.parse(sum));
        assertEquals(Optional.of(sum), ReleaseChecksums.parse("  " + sum.toUpperCase(Locale.ROOT) + "  x.jar\n"));
        assertEquals(Optional.empty(), ReleaseChecksums.parse("<html>build " + sum + " failed</html>"));
        assertEquals(Optional.empty(), ReleaseChecksums.parse(sum + "00"), "longer than a SHA-1");
        assertEquals(Optional.empty(), ReleaseChecksums.parse(null));
    }

    @Test
    void anAskerThatThrowsIsANo() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>(CommunityConsent.of(""));
        PluginGate gate = new PluginGate(() -> LISTED, REPO, () -> "", kept::get, kept::set, question -> {
            throw new IllegalStateException("no toolkit");
        }, jar -> true, jar -> true, (jar, row) -> true);

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
