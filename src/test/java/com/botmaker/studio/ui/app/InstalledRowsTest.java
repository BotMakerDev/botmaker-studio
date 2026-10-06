package com.botmaker.studio.ui.app;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Installed tab lists: one row per plugin, the copy kept, the plugins that came with another. */
class InstalledRowsTest {

    private static final String REPO = "/home/u/.m2/repository/com/github/";

    private static final UserLibrary OLD_SDK = new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "v1.2.3");
    private static final UserLibrary NEW_SDK =
            new UserLibrary("com.github.BotMakerDev", "botmaker-sdk", "1.3.1-SNAPSHOT");

    private static PluginHost.LoadedPlugin loaded(String id, String org, String artifact, String version) {
        return new PluginHost.LoadedPlugin(id, id, REPO + org + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + ".jar");
    }

    private static final PluginHost.LoadedPlugin SDK_LOADED =
            loaded("com.botmaker.sdk", "BotMakerDev", "botmaker-sdk", "1.3.1-SNAPSHOT");
    private static final PluginHost.LoadedPlugin BASICS_LOADED =
            loaded("com.botmaker.basics", "BotMakerDev", "botmaker-plugin-basics", "0.3.1-SNAPSHOT");

    @Test
    void aPluginDeclaredTwiceIsOneRowOnTheCopyStudioLoaded() {
        List<InstalledPlugin> found = InstalledPlugin.of(List.of(OLD_SDK, NEW_SDK), List.of(), lib -> true);

        List<InstalledPluginsTab.Declared> rows = InstalledPluginsTab.declaredOnce(found, List.of(SDK_LOADED));

        assertEquals(1, rows.size());
        assertEquals("com.github.BotMakerDev:botmaker-sdk", rows.getFirst().row().coordinate());
        assertEquals(List.of("com.github.LiQiyeDev:botmaker-sdk"),
                rows.getFirst().twins().stream().map(InstalledPlugin::coordinate).toList());
        assertTrue(InstalledPluginsTab.twinText(rows.getFirst().row(), rows.getFirst().twins())
                .contains("com.github.LiQiyeDev:botmaker-sdk v1.2.3"));
    }

    @Test
    void aPluginNoDeclarationAccountsForCameWithAnother() {
        assertEquals(List.of(BASICS_LOADED),
                InstalledPluginsTab.bundled(List.of(OLD_SDK, NEW_SDK), List.of(SDK_LOADED, BASICS_LOADED)));
    }

    @Test
    void theLoadedCellNamesTheJarsVersionAndADevBuild() {
        assertEquals("1.3.1-SNAPSHOT (dev build)", InstalledPluginsTab.loadedText(Optional.of(SDK_LOADED)));
        assertEquals("v1.2.3", InstalledPluginsTab.loadedText(
                Optional.of(loaded("com.botmaker.sdk", "LiQiyeDev", "botmaker-sdk", "v1.2.3"))));
        assertEquals("not loaded", InstalledPluginsTab.loadedText(Optional.empty()));
    }

    @Test
    void aLoadedJarBelongsToItsOwnGroupIdsRowOnly() {
        InstalledPlugin old = InstalledPlugin.of(List.of(OLD_SDK), List.of(), lib -> true).getFirst();

        assertTrue(InstalledPluginsTab.loadedFrom(old, List.of(SDK_LOADED)).isEmpty(),
                "the new groupId's jar is not the old row's");
    }
}
