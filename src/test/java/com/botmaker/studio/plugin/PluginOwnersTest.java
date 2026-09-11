package com.botmaker.studio.plugin;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns data in a project, read off the folders alone.
 *
 * <p>This is the one question about a plugin's data the host answers itself, because the plugin it is about
 * is the one that is not there. So the properties worth holding are about the <em>walk</em>: a two-segment id
 * and a one-segment id both come back spelled as the plugin spells them, an author's folder is not mistaken
 * for a plugin's, and an empty folder names nobody — a delete leaves one behind and it is not data anybody
 * is missing.
 *
 * <p>Nothing here loads a plugin. {@code absent} takes the "is it loaded" question as a predicate for the
 * same reason {@code VariablePicker.referencedVariable} does: the shape is testable headlessly and the
 * lookup is not.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class PluginOwnersTest {

    @TempDir
    Path resources;

    private void data(String... segments) throws IOException {
        Path dir = resources.resolve("plugins");
        for (String segment : segments) dir = dir.resolve(segment);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("parameters.json"), "{}");
    }

    @Test
    void anAuthorFolderAndAPluginFolderSpellTheId() throws IOException {
        data("com.botmaker", "sdk");
        assertEquals(List.of("com.botmaker.sdk"), PluginOwners.owners(resources));
    }

    @Test
    void anIdWithNoAuthorInItIsOneFolder() throws IOException {
        data("discord");
        assertEquals(List.of("discord"), PluginOwners.owners(resources));
    }

    @Test
    void everyOwnerIsReported() throws IOException {
        data("com.botmaker", "sdk");
        data("com.botmaker", "basics");
        data("com.example", "discord");
        assertEquals(Set.of("com.botmaker.sdk", "com.botmaker.basics", "com.example.discord"),
                Set.copyOf(PluginOwners.owners(resources)));
    }

    @Test
    void aFolderWithNoFileInItOwnsNothing() throws IOException {
        Files.createDirectories(resources.resolve("plugins").resolve("com.example").resolve("discord"));
        assertTrue(PluginOwners.owners(resources).isEmpty());
    }

    @Test
    void aProjectWithNoPluginsFolderOwnsNothing() {
        assertTrue(PluginOwners.owners(resources).isEmpty());
    }

    @Test
    void theOwnersNoPluginAnswersForAreTheAbsentOnes() {
        List<String> owners = List.of("com.botmaker.sdk", "com.example.discord");
        assertEquals(List.of("com.example.discord"),
                PluginOwners.absent(owners, "com.botmaker.sdk"::equals));
        assertTrue(PluginOwners.absent(owners, id -> true).isEmpty());
        assertEquals(owners, PluginOwners.absent(owners, id -> false));
    }
}
