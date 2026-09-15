package com.botmaker.studio.services.upgrade;

import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.parser.refactor.ApiMigrationRunner;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.BreakKind;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Operation;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Taking a plugin <b>out</b> — the fourth operation, and the one with no target jar.
 *
 * <p>It is the same engine with the second jar left empty, so what these tests are really about is the one
 * place removal and an upgrade have to disagree. A type the bot merely <b>calls</b> is repairable when the
 * plugin is going: the call becomes a default value or a deleted line, and the type name goes with it. A type
 * the bot <b>writes down</b> is not, and refuses the whole operation — which is the maintainer's constraint
 * working, since there is no value to stand in for {@code ImageTemplate t;}.
 *
 * <p>The third thing tested here has no equivalent in any other operation: the <b>import lines</b>. An
 * upgrade leaves the jar on the classpath, so an import that survives a rewrite still resolves; a removal
 * does not, and an import of a class that is no longer there is a compile error like any other.
 */
class PluginRemovalTest {

    private static final String PKG = UpgradeFixtures.PKG;

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(CacheDirs.cacheRoot().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /** One facade with a void call and a value call, and one type a bot could hold. */
    private static Map<String, String> plugin() {
        Map<String, String> m = new HashMap<>(UpgradeFixtures.withPointers(Map.of()));
        m.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click() {}
                    public static int count() { return 0; }
                }
                """.formatted(PKG));
        m.put("ImageTemplate", """
                package %s;
                public class ImageTemplate {
                    public static ImageTemplate named(String name) { return null; }
                }
                """.formatted(PKG));
        return m;
    }

    private static Report removalOver(Path tmp, String bot) throws IOException {
        return UpgradeFixtures.serviceOver(tmp, bot)
                .removal(UpgradeFixtures.jarOf(tmp, "only", plugin(), Map.of()), "1.0.0");
    }

    private static String rewriteOver(Path tmp, String bot) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, bot);
        ApiMigrationRunner.Outcome outcome = service.migrateRemoval(
                UpgradeFixtures.jarOf(tmp, "only", plugin(), Map.of()), Map.of());
        assertNotNull(outcome, "the removal had nothing to write");
        assertFalse(outcome.isRefusal(), () -> "the removal refused: " + outcome.refusal());
        return outcome.files().stream()
                .filter(f -> f.file().getPath().toString().endsWith("Subject.java"))
                .map(CallMigrator.Rewritten::newSource)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the bot's own file was not rewritten"));
    }

    // -------------------------------------------------------------------------
    // What the window is shown
    // -------------------------------------------------------------------------

    @Test
    void aRemovalReportSaysWhichOperationItIs(@TempDir Path tmp) throws IOException {
        Report report = removalOver(tmp, """
                package com.mybot;
                public class Subject {
                    public void run() { Mouse.click(); }
                }
                """);

        assertEquals(Operation.REMOVAL, report.operation());
        assertTrue(report.to().isBlank(), "a removal moves to no version at all");
        assertTrue(report.highlights().isEmpty(), "there is no release to read a changelog out of");
        assertTrue(report.added().isEmpty(), "nothing is added by taking a plugin away");
    }

    @Test
    void aCallOfARemovedPluginIsRepairableRatherThanARefusal(@TempDir Path tmp) throws IOException {
        Report report = removalOver(tmp, """
                package com.mybot;
                public class Subject {
                    public void run() { Mouse.click(); }
                }
                """);

        assertTrue(report.unrepairable().isEmpty(),
                () -> "a call has somewhere to go — it goes: " + report.unrepairable());
        assertEquals(1, report.breaks().size());
        assertEquals(BreakKind.MEMBER_REMOVED, report.breaks().getFirst().kind());
        assertTrue(report.canMigrate(), "the removal is repairable, so it may be made");
    }

    @Test
    void aTypeTheBotWritesDownRefusesTheRemoval(@TempDir Path tmp) throws IOException {
        Report report = removalOver(tmp, """
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageTemplate t = ImageTemplate.named("go");
                    }
                }
                """);

        Break refused = report.unrepairable().stream().findFirst().orElse(null);
        assertNotNull(refused, "a declaration has no value to stand in for, so it must refuse");
        assertEquals(BreakKind.TYPE_REMOVED, refused.kind());
        assertEquals("ImageTemplate", refused.type());
        assertFalse(refused.sites().isEmpty(), "the refusal has to name where the type is written");
        assertFalse(report.canMigrate());
    }

    // -------------------------------------------------------------------------
    // What is written
    // -------------------------------------------------------------------------

    @Test
    void removingDeletesAStatementAndDefaultsAValue(@TempDir Path tmp) throws IOException {
        String rewritten = rewriteOver(tmp, """
                package com.mybot;
                public class Subject {
                    public void run() {
                        Mouse.click();
                        int n = Mouse.count();
                    }
                }
                """);

        assertFalse(rewritten.contains("Mouse.click()"), "a statement call is deleted outright");
        assertFalse(rewritten.contains("Mouse.count()"), "a value call is replaced by a literal");
        assertTrue(rewritten.contains("int n = 0"), () -> "the value should be defaulted:\n" + rewritten);
        assertTrue(rewritten.contains("NeedsReview"), "the function that lost a call is marked");
    }

    @Test
    void removingDropsTheImportLinesThatNameThePlugin(@TempDir Path tmp) throws IOException {
        String rewritten = rewriteOver(tmp, """
                package com.mybot;
                import %s.Mouse;
                public class Subject {
                    public void run() { Mouse.click(); }
                }
                """.formatted(PKG));

        assertFalse(rewritten.contains("import " + PKG + ".Mouse"),
                () -> "the jar is gone, so the import no longer resolves:\n" + rewritten);
    }

    @Test
    void anImportOfAClassTheBotNeverCallsGoesToo(@TempDir Path tmp) throws IOException {
        // Nothing in the body names it, so no call scan and no type sweep would ever reach this line — and it
        // is exactly as broken as the ones that would.
        String rewritten = rewriteOver(tmp, """
                package com.mybot;
                import %s.ImageTemplate;
                public class Subject {
                    public void run() { Mouse.click(); }
                }
                """.formatted(PKG));

        assertFalse(rewritten.contains("import " + PKG + ".ImageTemplate"),
                () -> "an unused import of a removed plugin is still a compile error:\n" + rewritten);
    }

    @Test
    void theRefusalIsMadeAgainBeforeAnythingIsWritten(@TempDir Path tmp) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, """
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageTemplate t = ImageTemplate.named("go");
                    }
                }
                """);
        Path jar = UpgradeFixtures.jarOf(tmp, "only", plugin(), Map.of());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.migrateRemoval(jar, Map.of()));
        assertTrue(refused.getMessage().contains("ImageTemplate"),
                () -> "the refusal has to name the type: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("Nothing has been changed"));
    }
}
