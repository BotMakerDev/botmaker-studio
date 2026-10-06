package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalBuilds#scan(Path)} over a repository tree built here. Every case is a state a {@code ~/.m2}
 * reaches, and in each the answer is fewer builds, never an exception: the scan runs while a window opens.
 */
class LocalBuildsTest {

    private static final String SERVICE = "META-INF/services/com.botmaker.plugin.api.StudioPlugin";

    @TempDir
    Path repository;

    @Test
    void aSnapshotJarDeclaringAPluginIsFoundWithItsFullCoordinate() throws Exception {
        install("com/example/tools", "botmaker-discord", "0.1.0-SNAPSHOT", true, 1000);

        List<LocalBuilds.Build> found = LocalBuilds.scan(repository);

        assertEquals(1, found.size());
        assertEquals("com.example.tools:botmaker-discord", found.getFirst().coordinate());
        assertEquals("0.1.0-SNAPSHOT", found.getFirst().version());
    }

    @Test
    void aJarWithoutTheServiceFileIsNotAPlugin() throws Exception {
        install("com/example", "some-library", "0.1.0-SNAPSHOT", false, 1000);

        assertTrue(LocalBuilds.scan(repository).isEmpty());
    }

    @Test
    void aReleasedVersionIsADownloadNotABuild() throws Exception {
        install("com/example", "botmaker-discord", "1.2.0", true, 1000);

        assertTrue(LocalBuilds.scan(repository).isEmpty());
    }

    @Test
    void eachCoordinateIsItsNewestBuildAndTheNewestComesFirst() throws Exception {
        install("com/github/BotMakerDev", "botmaker-sdk", "1.3.1-SNAPSHOT", true, 1000);
        install("com/github/BotMakerDev", "botmaker-sdk", "1.4.1-SNAPSHOT", true, 3000);
        install("com/example", "mine", "0.1.0-SNAPSHOT", true, 2000);

        List<LocalBuilds.Build> found = LocalBuilds.scan(repository);

        assertEquals(List.of("com.github.BotMakerDev:botmaker-sdk", "com.example:mine"),
                found.stream().map(LocalBuilds.Build::coordinate).toList());
        assertEquals("1.4.1-SNAPSHOT", found.getFirst().version());
    }

    @Test
    void aSnapshotDownloadedFromARepositoryIsNoLocalBuild() throws Exception {
        install("com/github/someone", "their-plugin", "main-SNAPSHOT", true, 1000);
        Path dir = repository.resolve("com/github/someone/their-plugin/main-SNAPSHOT");
        Files.writeString(dir.resolve("_remote.repositories"), "their-plugin-main-SNAPSHOT.jar>jitpack.io=\n");
        install("com/example", "mine", "0.1.0-SNAPSHOT", true, 2000);
        Files.writeString(repository.resolve("com/example/mine/0.1.0-SNAPSHOT/_remote.repositories"),
                "mine-0.1.0-SNAPSHOT.jar>=\nmine-0.1.0-SNAPSHOT.pom>=\n");

        assertEquals(List.of("com.example:mine"),
                LocalBuilds.scan(repository).stream().map(LocalBuilds.Build::coordinate).toList());
    }

    @Test
    void aVersionDirectoryWithNoJarOrNoRepositoryIsEmpty() throws Exception {
        Files.createDirectories(repository.resolve("com/example/mine/0.1.0-SNAPSHOT"));

        assertTrue(LocalBuilds.scan(repository).isEmpty());
        assertTrue(LocalBuilds.scan(repository.resolve("nothing-here")).isEmpty());
    }

    /** One artifact in the repository layout, its jar dated {@code millis}. */
    private void install(String groupPath, String artifactId, String version, boolean plugin, long millis)
            throws Exception {
        Path dir = Files.createDirectories(repository.resolve(groupPath).resolve(artifactId).resolve(version));
        Path jar = dir.resolve(artifactId + "-" + version + ".jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            if (plugin) {
                zip.putNextEntry(new ZipEntry(SERVICE));
                zip.write("com.example.ExamplePlugin\n".getBytes());
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("com/example/Anything.class"));
            zip.closeEntry();
        }
        Files.setLastModifiedTime(jar, FileTime.fromMillis(millis));
    }
}
