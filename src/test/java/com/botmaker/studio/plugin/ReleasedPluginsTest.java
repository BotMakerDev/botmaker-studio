package com.botmaker.studio.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleasedPluginsTest {

    private static final String REPO = "/home/u/.m2/repository/com/github/LiQiyeDev/";
    private static final String SDK_DEV = REPO + "botmaker-sdk/0.0.0-SNAPSHOT/botmaker-sdk-0.0.0-SNAPSHOT.jar";
    private static final String SDK_TAG = REPO + "botmaker-sdk/1.2.3/botmaker-sdk-1.2.3.jar";
    private static final String SHARED_DEV = REPO + "botmaker-shared/0.0.0-SNAPSHOT/botmaker-shared-0.0.0-SNAPSHOT.jar";

    /** The plugins in these fixtures: the SDK at either version. */
    private static final Set<String> PLUGINS = Set.of(SDK_DEV, SDK_TAG);

    private static ReleasedPlugins.Split split(String... classpath) {
        return ReleasedPlugins.split(List.of(classpath), jar -> PLUGINS.contains(jar.toString()));
    }

    @Test
    void aDevBuildOfAPluginIsLeftOffAndReported() {
        ReleasedPlugins.Split split = split(SDK_DEV, SHARED_DEV);

        assertEquals(List.of(SHARED_DEV), split.loadable());
        assertEquals(1, split.refused().size());
        String line = split.refused().getFirst().describe();
        assertTrue(line.startsWith("botmaker-sdk 0.0.0-SNAPSHOT — a dev build"), line);
        assertTrue(line.contains("Plugins & Libraries"), line);
    }

    @Test
    void aReleasedPluginLoads() {
        ReleasedPlugins.Split split = split(SDK_TAG, SHARED_DEV);

        assertEquals(List.of(SDK_TAG, SHARED_DEV), split.loadable());
        assertTrue(split.refused().isEmpty());
    }

    @Test
    void aDevLibraryThatIsNoPluginStays() {
        // The bot's own SNAPSHOT dependencies are Maven's business; only a plugin is refused.
        assertEquals(List.of(SHARED_DEV), split(SHARED_DEV).loadable());
    }

    @Test
    void releasedJarsAreNeverOpened() {
        ReleasedPlugins.split(List.of(SDK_TAG), jar -> {
            throw new AssertionError("opened " + jar);
        });
    }

    @Test
    void devVersions() {
        assertTrue(ReleasedPlugins.isDevVersion("0.0.0-SNAPSHOT"));
        assertTrue(ReleasedPlugins.isDevVersion("2.0.0-snapshot"));
        assertTrue(ReleasedPlugins.isDevVersion("${botmaker.sdk.version}"));
        assertFalse(ReleasedPlugins.isDevVersion("1.2.3"));
        assertFalse(ReleasedPlugins.isDevVersion("v0.3.1"));
        assertFalse(ReleasedPlugins.isDevVersion(null));
        assertEquals("1.2.3", ReleasedPlugins.versionOf(Path.of(SDK_TAG)));
    }
}
