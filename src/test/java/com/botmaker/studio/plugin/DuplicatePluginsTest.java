package com.botmaker.studio.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One groupId's copy of each artifact a pom names under both of BotMaker's. */
class DuplicatePluginsTest {

    private static final String REPO = "/home/u/.m2/repository/";

    private static String jar(String org, String artifact, String version) {
        return REPO + "com/github/" + org + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }

    private static final String OLD_SDK = jar("LiQiyeDev", "botmaker-sdk", "v1.2.3");
    private static final String OLD_SHARED = jar("LiQiyeDev", "botmaker-shared", "v1.0.0");
    private static final String NEW_SDK = jar("BotMakerDev", "botmaker-sdk", "1.3.1-SNAPSHOT");
    private static final String NEW_SHARED = jar("BotMakerDev", "botmaker-shared", "1.1.0-SNAPSHOT");
    private static final String OTHER = REPO + "io/javalin/javalin/6.7.0/javalin-6.7.0.jar";

    /** The two SDK jars are plugins; nothing else is. */
    private static boolean plugin(Path jar) {
        return jar.toString().contains("botmaker-sdk");
    }

    @Test
    void theNewGroupIdsCopyIsKeptAndTheOldPluginJarIsReported() {
        DuplicatePlugins.Split split = DuplicatePlugins.split(
                List.of(OLD_SDK, OLD_SHARED, OTHER, NEW_SDK, NEW_SHARED), DuplicatePluginsTest::plugin);

        assertEquals(List.of(OTHER, NEW_SDK, NEW_SHARED), split.loadable(),
                "the old tree goes as a whole, or the new SDK runs on the old shared");
        assertEquals(1, split.dropped().size(), "a library left off is not a plugin failure");
        assertTrue(split.dropped().getFirst().provider().contains("botmaker-sdk"));
    }

    @Test
    void withNoNewPluginOnTheClasspathTheOldCopyIsKept() {
        // Outside dev mode the new SDK SNAPSHOT is refused before this runs, leaving only its tree's libraries.
        DuplicatePlugins.Split split = DuplicatePlugins.split(
                List.of(OLD_SDK, OLD_SHARED, NEW_SHARED), DuplicatePluginsTest::plugin);

        assertEquals(List.of(OLD_SDK, OLD_SHARED), split.loadable());
        assertTrue(split.dropped().isEmpty());
    }

    @Test
    void aClasspathWithOneCopyOfEverythingIsUntouched() {
        List<String> classpath = List.of(NEW_SDK, NEW_SHARED, OTHER);

        assertEquals(classpath, DuplicatePlugins.split(classpath, DuplicatePluginsTest::plugin).loadable());
    }

    @Test
    void theTwoGroupIdsNameTheSameArtifactAndNoOtherDoes() {
        assertTrue(DuplicatePlugins.sameArtifact("com.github.LiQiyeDev", "botmaker-sdk",
                "com.github.BotMakerDev", "botmaker-sdk"));
        assertFalse(DuplicatePlugins.sameArtifact("com.github.someone", "botmaker-sdk",
                "com.github.BotMakerDev", "botmaker-sdk"));
        assertFalse(DuplicatePlugins.sameArtifact("com.github.BotMakerDev", "botmaker-sdk",
                "com.github.BotMakerDev", "botmaker-shared"));
        assertEquals(Set.of("", "com.github.BotMakerDev"), Set.of(
                DuplicatePlugins.groupOf(Path.of(OTHER)), DuplicatePlugins.groupOf(Path.of(NEW_SDK))));
    }
}
