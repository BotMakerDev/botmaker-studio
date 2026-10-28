package com.botmaker.studio.services.upgrade;

import com.botmaker.shared.config.CacheDirs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What an upgrade report says about the release itself: what is new, grouped by the release it arrived in, the
 * release's own changelog for the span crossed, and which way the move goes.
 *
 * <p>What a move <em>breaks</em> is the migration engine's answer since 2026-10-16 ({@code migrate.Migration},
 * {@code MigrateEngineTest}): javac over the bot against both classpaths, not a diff of two jars. Two real jars
 * are still compiled here, because what is new is read off bytecode.
 */
class PluginUpgradeServiceTest {

    private static final String PKG = UpgradeFixtures.PKG;

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(CacheDirs.cacheRoot().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /** The version the bot is on. */
    private static Map<String, String> oldSdk() {
        return Map.of(
                "Mouse", """
                        package %s;
                        public class Mouse {
                            public static void click(int x, int y) {}
                        }
                        """.formatted(PKG),
                "Point", """
                        package %s;
                        public class Point {
                            public Point(int x, int y) {}
                        }
                        """.formatted(PKG));
    }

    /** The version it might move to: {@code Mouse.dragTo} and {@code Session} are new, {@code Point} untouched. */
    private static Map<String, String> newSdk() {
        return Map.of(
                "Mouse", """
                        package %s;
                        public class Mouse {
                            public static void click(int x, int y) {}
                            public static void dragTo(int x, int y) {}
                        }
                        """.formatted(PKG),
                "Session", """
                        package %s;
                        public class Session {
                            public static void open() {}
                        }
                        """.formatted(PKG),
                "Point", """
                        package %s;
                        public class Point {
                            public Point(int x, int y) {}
                        }
                        """.formatted(PKG));
    }

    private static Map<String, List<String>> additions(Path tmp, Map<String, String> before,
                                                       Map<String, String> after) throws IOException {
        return ApiModel.additions(ApiModel.snapshot(UpgradeFixtures.jarOf(tmp, "old", before, Map.of())),
                ApiModel.snapshot(UpgradeFixtures.jarOf(tmp, "new", after, Map.of())));
    }

    @Test
    void whatIsNewListsAddedClassesAndMembers(@TempDir Path tmp) throws IOException {
        List<String> added = additions(tmp, oldSdk(), newSdk()).values().stream().flatMap(List::stream).toList();

        assertTrue(added.contains("Session (new class)"), added.toString());
        assertTrue(added.contains("Mouse.dragTo(…)"), added.toString());
        assertFalse(added.contains("Point (new class)"), "an unchanged class is not new");
    }

    @Test
    void additionsAreGroupedByTheReleaseTheyArrivedIn(@TempDir Path tmp) throws IOException {
        Map<String, String> after = UpgradeFixtures.withSince(newSdk());
        after.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click(int x, int y) {}
                    @Since("2.0.0")
                    public static void dragTo(int x, int y) {}
                }
                """.formatted(PKG));
        after.put("Session", """
                package %s;
                @Since("1.5.0")
                public class Session {
                    public static void open() {}
                }
                """.formatted(PKG));
        Map<String, List<String>> bySince = additions(tmp, UpgradeFixtures.withSince(oldSdk()), after);

        assertEquals(List.of("2.0.0", "1.5.0"), List.copyOf(bySince.keySet()), "newest era first: " + bySince);
        assertEquals(List.of("Mouse.dragTo(…)"), bySince.get("2.0.0"));
        assertEquals(List.of("Session (new class)"), bySince.get("1.5.0"));
    }

    @Test
    void additionsFromAJarThatDeclaresNoErasAreOneUnlabelledGroup(@TempDir Path tmp) throws IOException {
        // The standing rule: a jar with none of this reads exactly as it did before the readers existed.
        Map<String, List<String>> bySince = additions(tmp, oldSdk(), newSdk());

        assertEquals(List.of(""), List.copyOf(bySince.keySet()), bySince.toString());
    }

    // -------------------------------------------------------------------------
    // What the release says about itself
    // -------------------------------------------------------------------------

    /** What the SDK's own build copies into every jar it publishes — see {@code botmaker-sdk/pom.xml}. */
    private static final String CHANGELOG = """
            # Changelog

            ## [Unreleased]

            - something not released yet, which no jar may ever show.

            ## [2.0.0] — 2026-08-24

            - `Mouse.dragTo` — drag without holding the button down yourself.
            - **Session** is new.

            ## [1.5.0] — 2026-08-20

            - the waiter stops spinning the CPU.

            ## [1.0.0] — 2026-08-01

            - the release the bot is already on.

            ## Earlier

            See `ROADMAP.md`.
            """;

    @Test
    void theReportCarriesTheReleasesTheBotIsMovingThrough(@TempDir Path tmp) throws IOException {
        Path jar = UpgradeFixtures.jarOf(tmp, "new", newSdk(), Map.of(WhatsNew.ENTRY, CHANGELOG));
        List<PluginUpgradeService.Highlight> highlights = WhatsNew.between(jar, "1.0.0", "2.0.0");

        assertEquals(List.of("2.0.0", "1.5.0"),
                highlights.stream().map(PluginUpgradeService.Highlight::version).toList(),
                "the span is (from, to], newest first: " + highlights);
        assertEquals("2026-08-24", highlights.get(0).date());
        // The emphasis markers go because a Label renders them literally; the bullet, the wording and the
        // author's punctuation all stay.
        assertEquals(List.of("- Mouse.dragTo — drag without holding the button down yourself.",
                        "- Session is new."),
                highlights.get(0).lines());
    }

    @Test
    void anSdkReleasedBeforeTheRenameIsReadUnderItsOldName(@TempDir Path tmp) throws IOException {
        Path jar = UpgradeFixtures.jarOf(tmp, "new", newSdk(), Map.of(WhatsNew.SDK_ENTRY, CHANGELOG));
        assertEquals(List.of("2.0.0", "1.5.0"), WhatsNew.between(jar, "1.0.0", "2.0.0").stream()
                .map(PluginUpgradeService.Highlight::version).toList());
    }

    @Test
    void aJarThatCarriesNoChangelogReadsExactlyAsItDidBefore(@TempDir Path tmp) throws IOException {
        // The standing rule. Every SDK up to v1.0.26 is this case, so it is the common one, not the edge.
        assertTrue(WhatsNew.between(UpgradeFixtures.jarOf(tmp, "new", newSdk(), Map.of()), "1.0.0", "2.0.0")
                .isEmpty());
    }

    @Test
    void theReleaseTheBotIsAlreadyOnIsNotNews() {
        assertEquals(List.of("2.0.0"),
                WhatsNew.parse(CHANGELOG, "1.5.0", "2.0.0").stream()
                        .map(PluginUpgradeService.Highlight::version).toList());
    }

    @Test
    void anUnreleasedSectionIsNeverInRange() {
        // It has no version, so it cannot be in a span — which is the whole of the special case it needs.
        assertTrue(WhatsNew.parse(CHANGELOG, "0.0.1", "9.9.9").stream()
                .noneMatch(h -> h.version().contains("Unrelease")));
        assertEquals(List.of("2.0.0", "1.5.0", "1.0.0"),
                WhatsNew.parse(CHANGELOG, "0.0.1", "9.9.9").stream()
                        .map(PluginUpgradeService.Highlight::version).toList());
    }

    @Test
    void aBoundThatIsNotAVersionIsSimplyNotApplied() {
        // A project pinned to a local 0.0.0-SNAPSHOT build is the case that actually happens. Showing every
        // section up to the target is a readable failure; showing none looks like a release that did nothing.
        assertEquals(List.of("2.0.0", "1.5.0", "1.0.0"),
                WhatsNew.parse(CHANGELOG, "0.0.0-SNAPSHOT", "2.0.0").stream()
                        .map(PluginUpgradeService.Highlight::version).toList());
    }

    @Test
    void aSectionOutOfOrderInTheFileIsStillShownNewestFirst() {
        String jumbled = """
                ## [1.5.0] — 2026-08-20

                - older, written first.

                ## [2.0.0] — 2026-08-24

                - newer, written second.
                """;
        assertEquals(List.of("2.0.0", "1.5.0"),
                WhatsNew.parse(jumbled, "1.0.0", "2.0.0").stream()
                        .map(PluginUpgradeService.Highlight::version).toList());
    }

    // =========================================================================
    // WHICH WAY THE MOVE GOES
    // =========================================================================

    @Test
    void aTargetOlderThanThePinnedVersionIsADowngradeAndANewerOneAnUpgrade() {
        assertEquals(PluginUpgradeService.Operation.DOWNGRADE,
                PluginUpgradeService.Report.operationFor("2.0.0", "1.0.0"));
        assertEquals(PluginUpgradeService.Operation.UPGRADE,
                PluginUpgradeService.Report.operationFor("1.0.0", "2.0.0"));
        assertEquals(PluginUpgradeService.Operation.UPGRADE,
                PluginUpgradeService.Report.operationFor("v1.0.0", "1.0.1"));
    }

    /** A row starts on the installed version unless the registry verified a newer one — never on a downgrade. */
    @Test
    void theSeedIsTheVerifiedVersionOnlyWhenItIsNewer() {
        assertEquals("1.2.3", PluginUpgradeService.recommended("1.2.3", "v1.1.7"));
        assertEquals("v1.2.4", PluginUpgradeService.recommended("1.2.3", "v1.2.4"));
        assertEquals("1.2.3", PluginUpgradeService.recommended("1.2.3", "v1.2.3"));
        assertEquals("1.2.3", PluginUpgradeService.recommended("1.2.3", ""));
        assertEquals("v1.1.7", PluginUpgradeService.recommended("", "v1.1.7"));
    }
}
