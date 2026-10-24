package com.botmaker.studio.plugin;

import com.botmaker.cli.catalog.Catalog;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
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
        assertTrue(asked.getFirst().question().contains("not a release the catalog approved (its newest is v1.0.0)"),
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
                }, jar -> true, jar -> false, (jar, row, version) -> ReleasePins.Verdict.UNCHECKED);
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
        }, jar -> !jar.toString().contains("slf4j"), jar -> !jar.toString().contains("downloaded"), PINNED);
    }

    /** Pins that always match: every approved release is the catalog's files. */
    private static final PluginGate.Pins PINNED = (jar, row, version) -> row.release(version).isPresent()
            ? ReleasePins.Verdict.APPROVED : ReleasePins.Verdict.NOT_APPROVED;

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
                question -> true, MavenService::declaresPlugin, jar -> false, PINNED);

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

    /** The pom beside an SDK jar of this test, {@code botmaker-sdk-<tag>.pom}. */
    private static Path pom(Path jar) {
        String name = jar.getFileName().toString();
        return jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".pom");
    }

    /** A release row pinning {@code jar} and the pom beside it at {@code tag}. */
    private static Catalog.Plugin.Release pinned(String tag, Path jar) {
        return new Catalog.Plugin.Release(tag, "a".repeat(40), ReleasePins.sha256(jar).orElseThrow(),
                ReleasePins.sha256(pom(jar)).orElseThrow(), "", List.of());
    }

    private static PluginRegistry.Plugin sdkApproving(List<Catalog.Plugin.Release> releases) {
        return new PluginRegistry.Plugin("com.botmaker.sdk", "SDK", "com.github.BotMakerDev:botmaker-sdk",
                "BotMakerDev/botmaker-sdk", "", List.of(), "", List.of(), "", releases.getFirst().tag(),
                PublisherRank.ADMIN, 0, releases);
    }

    /** 2026-10-24: the jar and its pom are the files the catalog pinned, or the release is not vouched for. */
    @Test
    void aJarIsApprovedOnlyWhenItAndItsPomAreThePinnedFiles(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("botmaker-sdk-v1.0.0.jar");
        Files.writeString(jar, "the release");
        Files.writeString(pom(jar), "<project/>");
        assertEquals(dir.resolve("botmaker-sdk-v1.0.0.pom"),
                ReleasePins.pomOf(dir.resolve("botmaker-sdk-v1.0.0-all.jar"), "botmaker-sdk", "v1.0.0"),
                "a classifier's jar shares its version's pom");
        Set<String> seen = new LinkedHashSet<>();
        ReleasePins pins = new ReleasePins(() -> seen, next -> {
            seen.clear();
            seen.addAll(next);
        });
        PluginRegistry.Plugin row = sdkApproving(List.of(pinned("v1.0.0", jar)));

        assertEquals(ReleasePins.Verdict.APPROVED, pins.check(jar, row, "v1.0.0"));
        assertEquals(Set.of("com.github.BotMakerDev:botmaker-sdk@v1.0.0"), seen);
        assertEquals(ReleasePins.Verdict.NOT_APPROVED, pins.check(jar, row, "v0.9.0"));
        assertEquals(ReleasePins.Verdict.UNCHECKED, pins.check(jar, SDK, "v1.0.0"), "a release with no pins");

        Files.writeString(pom(jar), "<project><repositories/></project>");
        assertEquals(ReleasePins.Verdict.CHANGED, pins.check(jar, row, "v1.0.0"), "the pom names the downloads");
        Files.delete(pom(jar));
        assertEquals(ReleasePins.Verdict.UNCHECKED, pins.check(jar, row, "v1.0.0"));
    }

    /** Review focus 5: a release this computer saw approved and the catalog dropped reads as removed. */
    @Test
    void aReleaseSeenApprovedThatTheCatalogDroppedIsRemoved(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("botmaker-sdk-v1.0.0.jar");
        Files.writeString(jar, "the release");
        Files.writeString(pom(jar), "<project/>");
        Set<String> seen = new LinkedHashSet<>(Set.of("com.github.BotMakerDev:botmaker-sdk@v1.0.0"));
        ReleasePins pins = new ReleasePins(() -> seen, seen::addAll);
        Path newer = dir.resolve("botmaker-sdk-v1.1.0.jar");
        Files.writeString(newer, "the next release");
        Files.writeString(pom(newer), "<project/>");

        assertEquals(ReleasePins.Verdict.REMOVED, pins.check(jar, sdkApproving(List.of(pinned("v1.1.0", newer))),
                "v1.0.0"));
    }

    @Test
    void aChangedOrRemovedReleaseIsRefusedWithoutAsking() {
        PluginRegistry.Plugin row = sdkApproving(List.of(
                new Catalog.Plugin.Release("v1.1.0", null, null, null, "", List.of())));
        CatalogIndex listed = new CatalogIndex(List.of(row), List.of());
        List<PluginGate.Asked> asked = new ArrayList<>();
        for (ReleasePins.Verdict verdict : List.of(ReleasePins.Verdict.CHANGED, ReleasePins.Verdict.REMOVED)) {
            PluginGate gate = new PluginGate(() -> listed, REPO, () -> "", () -> CommunityConsent.of(""), kept -> {},
                    question -> {
                        asked.add(question);
                        return true;
                    }, jar -> true, jar -> false, (jar, r, version) -> verdict);
            PluginGate.Admitted admitted = gate.admit(listed, List.of(SDK_JAR), List.of());
            assertTrue(admitted.loadable().isEmpty(), verdict.name());
            assertEquals("botmaker-sdk v1.0.0 — " + PluginGate.refusal(verdict, Optional.of(row), "v1.0.0")
                    .orElseThrow(), admitted.refused().getFirst().describe());
        }
        assertTrue(asked.isEmpty());
        assertEquals(Optional.of("not loaded: SDK v1.0.0 was removed from the catalog. Upgrade it to v1.1.0 in "
                + "Plugins & Libraries"), PluginGate.refusal(ReleasePins.Verdict.REMOVED, Optional.of(row), "v1.0.0"));
        assertTrue(PluginGate.refusal(ReleasePins.Verdict.CHANGED, Optional.of(row), "v1.0.0").orElseThrow()
                .contains("changed after the catalog approved it"));
        assertEquals(Optional.empty(), PluginGate.refusal(ReleasePins.Verdict.NOT_APPROVED, Optional.of(row), "v9"));
    }

    /** 2026-10-24: an official row vouches for each release it approved, not its newest alone. */
    @Test
    void anOlderApprovedReleaseOfAnOfficialRowIsNotAsked() {
        PluginRegistry.Plugin row = sdkApproving(List.of(
                new Catalog.Plugin.Release("v1.1.0", null, null, null, "", List.of()),
                new Catalog.Plugin.Release("v1.0.0", null, null, null, "", List.of())));
        assertFalse(CommunityConsent.needsAsking(row.coordinate(), "v1.0.0", Optional.of(row), true, "", false,
                RELEASE));
        assertEquals(List.of("v1.1.0", "v1.0.0"), row.approvedTags());
    }

    /** One preference holds 8192 characters: the oldest releases seen are dropped first. */
    @Test
    void theReleasesSeenKeepTheNewestThatFit() {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < 400; i++) keys.add("com.github.BotMakerDev:botmaker-sdk@v1.0." + i);
        String stored = ReleasePins.stored(keys);
        assertTrue(stored.length() <= Preferences.MAX_VALUE_LENGTH);
        assertTrue(stored.endsWith("@v1.0.399"));
        assertFalse(ReleasePins.of(stored).contains("com.github.BotMakerDev:botmaker-sdk@v1.0.0"));
    }

    @Test
    void anAskerThatThrowsIsANo() {
        AtomicReference<CommunityConsent> kept = new AtomicReference<>(CommunityConsent.of(""));
        PluginGate gate = new PluginGate(() -> LISTED, REPO, () -> "", kept::get, kept::set, question -> {
            throw new IllegalStateException("no toolkit");
        }, jar -> true, jar -> true, PINNED);

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
