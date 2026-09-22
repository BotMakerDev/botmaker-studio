package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.sharing.PluginRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manage Plugins refusing to declare a plugin another plugin already brings.
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

    private static PluginRegistry.Plugin entry(String id, String name, String coordinate) {
        return new PluginRegistry.Plugin(id, name, coordinate, "", "", List.of(), "", List.of(),
                "1.0.0", "");
    }

    private static final PluginRegistry.Plugin BASICS =
            entry("com.botmaker.basics", "Basics", "com.github.LiQiyeDev:botmaker-plugin-basics");

    @Test
    void aPluginAnotherPluginBringsIsRefusedWithTheReason() {
        String refusal = ManagePluginsDialog.alreadyProvided(BASICS,
                List.of(new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "1.1.12")),
                List.of("com.botmaker.sdk", "com.botmaker.basics"));

        assertTrue(refusal.contains("Basics"), refusal);
        assertTrue(refusal.contains("already on this project's classpath"), refusal);
        assertTrue(refusal.contains("version"), "it says what to do instead: " + refusal);
    }

    @Test
    void aPluginThePomAlreadyDeclaresIsNeverRefused() {
        assertEquals("", ManagePluginsDialog.alreadyProvided(BASICS,
                List.of(new UserLibrary("com.github.LiQiyeDev", "botmaker-plugin-basics", "0.0.5")),
                List.of("com.botmaker.sdk", "com.botmaker.basics")),
                "re-installing is idempotent by coordinate and must stay possible");
    }

    @Test
    void aPluginNothingBringsInstallsNormally() {
        assertEquals("", ManagePluginsDialog.alreadyProvided(BASICS, List.of(), List.of("com.botmaker.sdk")));
    }

    /** An entry with no id is every entry written before the registry had one; it is never refused. */
    @Test
    void anEntryWithNoIdIsNeverRefused() {
        PluginRegistry.Plugin anonymous = entry("", "Nameless", "g:a");

        assertEquals("", ManagePluginsDialog.alreadyProvided(anonymous, List.of(), List.of("", "x")));
    }
}
