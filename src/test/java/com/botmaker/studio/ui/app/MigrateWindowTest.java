package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.migrate.Broken;
import com.botmaker.studio.migrate.OldElement;
import com.botmaker.studio.migrate.PluginRules;
import com.botmaker.studio.migrate.Rewrite;
import com.botmaker.studio.migrate.Rule;
import com.botmaker.studio.migrate.Sites;
import com.botmaker.studio.nav.Uses;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Migrate, its banner and the assistant's {@code migration} tool say, without a screen. */
class MigrateWindowTest {

    private static final OldElement MARKER = new OldElement(Migrate.Kind.METHOD, "com.bot.api.GameScreen", "marker",
            List.of("com.bot.api.ImageTemplate"), true, "com.bot.api.GameScreen", "marker-key");
    private static final OldElement OTHER_MARKER = new OldElement(Migrate.Kind.METHOD, "com.other.GameScreen",
            "marker", List.of("com.other.ImageTemplate"), true, "com.other.GameScreen", "other-key");

    private static Sites.Site site(int line) {
        return new Sites.Site(Path.of("Screens.java"), MARKER, Uses.Shape.CALL, 0, 1, line, "Screens.HOME");
    }

    private static Rule rule(boolean seeded, String problem) {
        return new Rule(Path.of("Migrations.java"), "Migrations", "marker", 0, Migrate.Kind.METHOD,
                "com.bot.api.GameScreen", List.of(), List.of(), true, "self", false, "GameScreen", Set.of(),
                Set.of(), seeded, MARKER, problem, null);
    }

    @Test
    void theBannerCountsWhatTheLastChangeBroke() {
        String one = new MigrateWindow.Pending(1, false).text();
        String three = new MigrateWindow.Pending(3, false).text();
        assertTrue(one.startsWith("1 old element the bot uses was broken"), one);
        assertTrue(three.startsWith("3 old elements the bot uses were broken"), three);
    }

    @Test
    void theBannerSaysADeprecatedUseHasItsPluginsRuleNotThatItBroke() {
        String two = new MigrateWindow.Pending(2, true).text();
        assertTrue(two.startsWith("2 deprecated elements the bot uses can be migrated"), two);
        assertTrue(!two.contains("broken"), two);
    }

    @Test
    void theHeadingSaysWhatTheBotIsComparedWith() {
        assertTrue(MigrateWindow.headingText(List.of(), false).startsWith("Compared with the jars"));
        assertTrue(MigrateWindow.headingText(List.of(), true).startsWith("No plugin changed"));
    }

    @Test
    void nothingLeftSaysNothing() {
        assertEquals("", MigrateWindow.leftText(List.of()));
    }

    @Test
    void theToolSaysEachRuleAndWhatToDoNext() {
        assertEquals("no rule (seed)", MigrateWindow.toolRuleText(new Broken(MARKER, List.of(site(3)),
                Broken.State.NONE, null)));
        assertEquals("rule Migrations.marker still seeded: write its body", MigrateWindow.toolRuleText(
                new Broken(MARKER, List.of(), Broken.State.SEEDED, rule(true, null))));
        assertEquals("rule Migrations.marker", MigrateWindow.toolRuleText(
                new Broken(MARKER, List.of(), Broken.State.WRITTEN, rule(false, null))));
        assertEquals("rule Migrations.marker cannot be applied: twice", MigrateWindow.toolRuleText(
                new Broken(MARKER, List.of(), Broken.State.REFUSED, rule(false, "twice"))));
    }

    @Test
    void aPluginsRuleSaysWhoseItIsAndMayBeWrittenOver() {
        Rule shipped = new Rule(Path.of("lib/Migrations.java"), "Migrations", "marker", 0, Migrate.Kind.METHOD,
                "com.bot.api.GameScreen", List.of(), List.of(), true, "self", false, "GameScreen", Set.of(),
                Set.of(), false, MARKER, null, new PluginRules.Plugin(PluginCoordinate.parse("fixture:sdk"),
                "BotMaker SDK", Path.of("sdk.jar")));
        Broken fromPlugin = new Broken(MARKER, List.of(site(3)), Broken.State.WRITTEN, shipped);
        assertEquals("Migrations.marker, from BotMaker SDK", MigrateWindow.ruleText(fromPlugin));
        assertTrue(MigrateWindow.toolRuleText(fromPlugin).startsWith("rule Migrations.marker, from BotMaker SDK"
                + " (read-only"), MigrateWindow.toolRuleText(fromPlugin));
        assertTrue(MigrateWindow.seedable(fromPlugin));
        assertTrue(MigrateWindow.seedable(new Broken(MARKER, List.of(), Broken.State.NONE, null)));
        assertFalse(MigrateWindow.seedable(new Broken(MARKER, List.of(), Broken.State.WRITTEN, rule(false, null))));
    }

    @Test
    void anElementIsNamedBackAsTheListSaysIt() {
        Broken marker = new Broken(MARKER, List.of(site(3)), Broken.State.NONE, null);
        assertSame(marker, MigrateWindow.named(List.of(marker), " GameScreen#marker(ImageTemplate) "));
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> MigrateWindow.named(List.of(marker), "GameScreen#markers"));
        assertTrue(none.getMessage().contains("migration list names them: GameScreen#marker(ImageTemplate)."),
                none.getMessage());
        Broken other = new Broken(OTHER_MARKER, List.of(), Broken.State.NONE, null);
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> MigrateWindow.named(List.of(marker, other), "GameScreen#marker(ImageTemplate)"));
        assertTrue(twice.getMessage().startsWith("Two old elements are both"), twice.getMessage());
    }

    @Test
    void eachRewrittenUseIsSaidBeforeAndAfter() {
        assertEquals("- Screens.java:3: x.marker(a) → x.markers(List.of(a))\n- Screens.java:4: y.popups(p) → (deleted)",
                MigrateWindow.changesText(List.of(new Rewrite.Change(site(3), "x.marker(a)", "x.markers(List.of(a))"),
                        new Rewrite.Change(site(4), "y.popups(p)", ""))));
    }
}
