package com.botmaker.studio.services;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.sharing.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pilot's javalin, lost from a pom and put back (2026-10-07): the {@code com.github.LiQiyeDev} copy of an
 * SDK declared twice was removed, and its companions went with it.
 */
class EditorCompanionsTest {

    private static final UserLibrary JAVALIN = new UserLibrary("io.javalin", "javalin", "6.7.0");
    private static final UserLibrary ZXING = new UserLibrary("com.google.zxing", "core", "3.5.3");

    private static PluginRegistry.Plugin sdkEntry(String group) {
        String coordinate = group + ":botmaker-sdk";
        return new PluginRegistry.Plugin("com.botmaker.sdk", "BotMaker SDK", coordinate, "BotMakerDev/botmaker-sdk",
                "", List.of(), "", List.of("io.javalin:javalin:6.7.0", "com.google.zxing:core:3.5.3"), "v1.2.3", "");
    }

    @Test
    void anEntryUnderTheFormerGroupIdStillNamesTheCompanionsOfTheNewOne() {
        List<UserLibrary> declared = List.of(new UserLibrary("com.github.BotMakerDev", "botmaker-sdk", "v1.2.3"));

        assertEquals(List.of(JAVALIN, ZXING),
                EditorCompanions.missing(declared, List.of(sdkEntry("com.github.LiQiyeDev"))));
    }

    @Test
    void aCompanionAlreadyDeclaredIsNotMissingAndAnUndeclaredPluginAsksNothing() {
        List<UserLibrary> declared = List.of(
                new UserLibrary("com.github.BotMakerDev", "botmaker-sdk", "v1.2.3"), JAVALIN);

        assertEquals(List.of(ZXING), EditorCompanions.missing(declared, List.of(sdkEntry("com.github.BotMakerDev"))));
        assertEquals(List.of(), EditorCompanions.missing(List.of(JAVALIN), List.of(sdkEntry("com.github.BotMakerDev"))));
    }

    @Test
    void removingOneCopyOfAPluginDeclaredTwiceKeepsTheCompanionsTheOtherNeeds(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.x</groupId><artifactId>x</artifactId><version>1</version>
                  <dependencies>
                    <dependency><groupId>com.github.LiQiyeDev</groupId><artifactId>botmaker-sdk</artifactId><version>v1.1.7</version></dependency>
                    <dependency><groupId>io.javalin</groupId><artifactId>javalin</artifactId><version>6.7.0</version><scope>provided</scope></dependency>
                    <dependency><groupId>com.google.zxing</groupId><artifactId>core</artifactId><version>3.5.3</version><scope>provided</scope></dependency>
                    <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-sdk</artifactId><version>v1.2.3</version></dependency>
                  </dependencies>
                </project>
                """);

        MavenService.removePlugin(dir, "com.github.LiQiyeDev", "botmaker-sdk", List.of(JAVALIN, ZXING));

        List<UserLibrary> left = MavenService.readDeclaredLibraries(dir);
        assertEquals(List.of("javalin", "core", "botmaker-sdk"), left.stream().map(UserLibrary::artifactId).toList());
        assertTrue(left.stream().anyMatch(l -> l.groupId().equals("com.github.BotMakerDev")));

        MavenService.removePlugin(dir, "com.github.BotMakerDev", "botmaker-sdk", List.of(JAVALIN, ZXING));

        assertEquals(List.of(), MavenService.readDeclaredLibraries(dir), "the last copy takes its companions");
    }

    @Test
    void declaringCompanionsWritesOnlyWhatIsAbsent(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.x</groupId><artifactId>x</artifactId><version>1</version>
                  <dependencies>
                    <dependency><groupId>io.javalin</groupId><artifactId>javalin</artifactId><version>6.7.0</version><scope>provided</scope></dependency>
                  </dependencies>
                </project>
                """);

        assertEquals(List.of(ZXING), MavenService.declareCompanions(dir, List.of(JAVALIN, ZXING)));
        assertEquals(List.of(), MavenService.declareCompanions(dir, List.of(JAVALIN, ZXING)));
        assertTrue(Files.readString(dir.resolve("pom.xml")).contains("<scope>provided</scope>"));
    }
}
