package com.botmaker.studio.services.upgrade;

import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.botmaker.studio.services.upgrade.UpgradeFixtures.jarOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two plugins, one simple name — the hazard a project upgrade has that an SDK upgrade never did.
 *
 * <p>Call sites are judged from source alone, without bindings, so a call is attributed to a plugin when
 * the source writes that plugin's type name. Two plugins declaring {@code Point} make {@code Point.of(…)}
 * genuinely unanswerable: it is not that the right answer is hard to compute, it is that the source does
 * not contain one. <b>Guessing is the worst available outcome</b> — the report would name a break in a
 * class the bot never touched, and the repair would then rewrite it there, producing a bot that compiles
 * and does something else.
 *
 * <p>So the verdict is {@code MethodReferences}' three-way one: one owner is a match, several is a line in
 * {@link Report#problems()}, none is not a reference. A problem stops the whole report and the whole
 * rewrite, which is the same all-or-nothing rule a file that does not parse already gets.
 *
 * <p>Two jars are compiled here rather than mocked, in two different packages, because the clash being
 * tested is a property of what two plugins actually declare.
 */
class TwoPluginCollisionTest {

    private static final String SDK_PKG = UpgradeFixtures.PKG;
    private static final String OTHER_PKG = "com.example.shapes";

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(CacheDirs.cacheRoot().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /** Plugin #1: a {@code Point} of its own, plus a facade nobody else has. */
    private static Map<String, String> sdkJar() {
        return Map.of(
                "Point", """
                        package %s;
                        public class Point {
                            public static Point of(int x, int y) { return new Point(); }
                        }
                        """.formatted(SDK_PKG),
                "Mouse", """
                        package %s;
                        public class Mouse {
                            public static void click(int x, int y) {}
                        }
                        """.formatted(SDK_PKG));
    }

    /** Plugin #2: its own {@code Point}, in its own package, and a {@code Rect} nobody else has. */
    private static Map<String, String> otherJar() {
        return Map.of(
                "Point", """
                        package %s;
                        public class Point {
                            public static Point of(int x, int y) { return new Point(); }
                        }
                        """.formatted(OTHER_PKG),
                "Rect", """
                        package %s;
                        public class Rect {}
                        """.formatted(OTHER_PKG));
    }

    private static final String BOT = """
            package com.mybot;

            public class Subject {
                public static void run() {
                    Mouse.click(1, 2);
                    Point where = Point.of(3, 4);
                }
            }
            """;

    /** What {@code InstalledPlugin} would hand the service, computed from the two real jars. */
    private static Set<String> clashesBetween(Path tmp) throws IOException {
        Path sdk = jarOf(tmp, SDK_PKG, "sdk", sdkJar(), Map.of());
        Path other = jarOf(tmp, OTHER_PKG, "other", otherJar(), Map.of());
        return InstalledPlugin.ambiguousTypeNames(Map.of(
                "com.github.LiQiyeDev:botmaker-sdk", ApiModel.snapshot(sdk).keySet(),
                "com.example:shapes", ApiModel.snapshot(other).keySet()));
    }

    @Test
    void twoJarsInDifferentPackagesStillClashOnTheSimpleNameTheSourceWrites(@TempDir Path tmp)
            throws IOException {
        assertEquals(Set.of("Point"), clashesBetween(tmp),
                "the packages differ and the source names neither of them, which is the whole problem");
    }

    @Test
    void aClashRefusesTheReportInsteadOfAttributingTheCall(@TempDir Path tmp) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, clashesBetween(tmp), BOT);
        Report report = service.compare(
                jarOf(tmp, SDK_PKG, "old", sdkJar(), Map.of()),
                jarOf(tmp, SDK_PKG, "new", sdkJar(), Map.of()), "1.0.0", "2.0.0");

        assertTrue(report.isIncomplete(), "a clash must never read as \"nothing breaks\"");
        assertEquals(1, report.problems().size(), report.problems()::toString);
        assertTrue(report.problems().getFirst().contains("Point"),
                () -> "the problem must name the type the user has to act on: " + report.problems());
        assertTrue(report.breaks().isEmpty(),
                "nothing may be reported about a scan that was refused before it ran");
    }

    /**
     * The refusal is scoped to the name, not to the project. A plugin whose types nobody else declares is
     * upgraded normally even while some <em>other</em> pair of plugins is ambiguous — otherwise one bad
     * clash would freeze every row in the window.
     */
    @Test
    void aPluginWhoseNamesAreItsOwnIsUnaffectedByAClashElsewhere(@TempDir Path tmp) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, Set.of("Widget", "Sprite"), BOT);
        Report report = service.compare(
                jarOf(tmp, SDK_PKG, "old", sdkJar(), Map.of()),
                jarOf(tmp, SDK_PKG, "new", sdkJar(), Map.of()), "1.0.0", "2.0.0");

        assertEquals(List.of(), report.problems());
        assertTrue(report.nothingBreaks(), "the jars are identical, so there is nothing to report");
    }

    /**
     * The rewrite is refused too, and by the same problem list. {@code migrate} throws on the first problem
     * exactly as it does for a file that does not parse — a report the user could not act on must not be
     * followed by a button that writes anyway.
     */
    @Test
    void theRewriteIsRefusedToo(@TempDir Path tmp) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, clashesBetween(tmp), BOT);
        Path old = jarOf(tmp, SDK_PKG, "old", sdkJar(), Map.of());
        Path now = jarOf(tmp, SDK_PKG, "new", sdkJar(), Map.of());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.migrate(old, now, "1.0.0", "2.0.0", false, true, Map.of()));
        assertTrue(refused.getMessage().contains("Point"), refused::getMessage);
    }
}
