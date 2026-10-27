package com.botmaker.studio.ui.app;

import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.sharing.PluginRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugins &amp; Libraries ▸ Browse refusing to declare a plugin another plugin already brings.
 *
 * <p>The rule is the umbrella's: a plugin-to-plugin dependency is an ordinary Maven dependency, and nothing
 * may declare the depended-on plugin <em>beside</em> the plugin that brings it — Maven's nearest-wins would
 * then let the bot's own pom pin a version that plugin was never built against, and what that produces is a
 * linkage error inside somebody else's code.
 *
 * <p>Bound-but-not-declared is the whole test: a plugin the pom names is the user's own choice and a
 * re-install or a version change must stay possible, so being installed wins over being bound.
 */
class PluginAlreadyProvidedTest {

    private static PluginRegistry.Plugin entry(String name, String coordinate) {
        return new PluginRegistry.Plugin(name, coordinate, "", "", List.of(), "", List.of(),
                "1.0.0");
    }

    private static final PluginRegistry.Plugin BASICS =
            entry("Basics", "com.github.BotMakerDev:botmaker-plugin-basics");
    private static final PluginCoordinate SDK_BOUND = new PluginCoordinate("com.github.BotMakerDev", "botmaker-sdk");
    /** Bound under BotMaker's former groupId: still the catalog's basics. */
    private static final PluginCoordinate BASICS_BOUND =
            new PluginCoordinate("com.github.LiQiyeDev", "botmaker-plugin-basics");

    @Test
    void aPluginAnotherPluginBringsIsRefusedWithTheReason() {
        String refusal = BrowsePluginsTab.alreadyProvided(BASICS,
                List.of(new UserLibrary("com.github.BotMakerDev", "botmaker-sdk", "1.1.12")),
                List.of(SDK_BOUND, BASICS_BOUND));

        assertTrue(refusal.contains("Basics"), refusal);
        assertTrue(refusal.contains("already on this project's classpath"), refusal);
        assertTrue(refusal.contains("version"), "it says what to do instead: " + refusal);
    }

    @Test
    void aPluginThePomAlreadyDeclaresIsNeverRefused() {
        assertEquals("", BrowsePluginsTab.alreadyProvided(BASICS,
                List.of(new UserLibrary("com.github.BotMakerDev", "botmaker-plugin-basics", "0.0.5")),
                List.of(SDK_BOUND, BASICS_BOUND)),
                "re-installing is idempotent by coordinate and must stay possible");
    }

    @Test
    void aLocalBuildsRowIsRefusedWhenItsJarIsAlreadyOnTheClasspath() {
        // A dev-mode row for a build no registry lists, with nothing bound: only the jar can tell.
        PluginRegistry.Plugin local = entry("botmaker-plugin-basics", "com.github.BotMakerDev:botmaker-plugin-basics");
        List<String> classpath = List.of("/home/u/.m2/repository/com/github/BotMakerDev/botmaker-plugin-basics/"
                + "0.3.1-SNAPSHOT/botmaker-plugin-basics-0.3.1-SNAPSHOT.jar");

        assertTrue(BrowsePluginsTab.alreadyProvided(local, List.of(), List.of(), classpath)
                .contains("already on this project's classpath"));
        assertEquals("", BrowsePluginsTab.alreadyProvided(local, List.of(), List.of(), List.of()));
    }

    @Test
    void aPluginNothingBringsInstallsNormally() {
        assertEquals("", BrowsePluginsTab.alreadyProvided(BASICS, List.of(), List.of(SDK_BOUND)));
    }

    /** An entry whose coordinate does not parse matches no bound plugin; it is never refused for one. */
    @Test
    void anEntryWithNoCoordinateIsNeverRefused() {
        PluginRegistry.Plugin anonymous = entry("Nameless", "");

        assertEquals("", BrowsePluginsTab.alreadyProvided(anonymous, List.of(), List.of(SDK_BOUND)));
    }
}
