package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project is a directory, not a name under one root (2026-09-18). These hold the three places that
 * learnt it: {@link ProjectConfig#forDirectory}, {@link ProjectPreferences}' recents, and
 * {@link ProjectManager}'s refusal to move a folder it does not keep. Everything runs under a {@link TempDir};
 * nothing reads or writes the user's own {@code ~/BotMakerProjects}.
 */
class ProjectDirectoryTest {

    @TempDir
    Path tmp;

    private static Path project(Path dir, String pkgPath) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java").resolve(pkgPath));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        return dir;
    }

    // ── ProjectConfig ────────────────────────────────────────────────────────

    @Test
    void aDirectoryUnderTheRootReadsExactlyAsItsNameDoes() throws IOException {
        Path dir = project(tmp.resolve("MyBot"), "com/mybot");
        assertEquals(ProjectConfig.forProject("MyBot", tmp), ProjectConfig.forDirectory(dir));
    }

    @Test
    void aTemplatesPackageOfMainWinsOverItsFolderName() throws IOException {
        // The case that forced it: botmaker-gamebot holds com.botmaker.gamebot, and nothing derives one
        // from the other.
        Path dir = project(tmp.resolve("repos/botmaker-gamebot"), "com/botmaker/gamebot");
        Path entry = dir.resolve("src/main/java/com/botmaker/gamebot/Gamebot.java");
        Files.writeString(entry, "package com.botmaker.gamebot;\nclass Gamebot { public static void main(String[] a) {} }");

        ProjectConfig config = ProjectConfig.forDirectory(dir);

        assertEquals("botmaker-gamebot", config.projectName());
        assertEquals("com.botmaker.gamebot", config.mainPackage());
        assertEquals(dir.resolve("src/main/java/com/botmaker/gamebot"), config.mainPackageDir(),
                "one directory per package segment, not one directory called botmaker.gamebot");
        assertEquals(entry, config.entrySourceFile());
        assertEquals("com.botmaker.gamebot.Gamebot", config.entryClassName());
    }

    @Test
    void aMainOutsideComIsIgnoredRatherThanMisread() throws IOException {
        Path dir = project(tmp.resolve("Odd"), "com/odd");
        Path other = Files.createDirectories(dir.resolve("src/main/java/org/example"));
        Files.writeString(other.resolve("Tool.java"),
                "package org.example;\nclass Tool { public static void main(String[] a) {} }");
        assertEquals("com.odd", ProjectConfig.forDirectory(dir).mainPackage());
    }

    // ── ProjectPreferences ───────────────────────────────────────────────────

    @Test
    void recentsRememberWhereAProjectIs() {
        Path file = tmp.resolve("botmaker-config.json");
        Path outside = tmp.resolve("repos/botmaker-gamebot");

        ProjectPreferences prefs = new ProjectPreferences();
        prefs.recordOpened(outside);
        prefs.write(file);

        ProjectPreferences read = ProjectPreferences.read(file);
        assertEquals(outside, read.lastOpenedDirectory());
        assertEquals("botmaker-gamebot", read.getLastOpenedProject(),
                "the name is still written, so an older Studio reading this file still has one");
        assertEquals(List.of(outside), read.getRecentProjects().stream().map(ProjectPreferences.ProjectEntry::directory).toList());
    }

    @Test
    void aFileAnOlderStudioWroteMeansTheDefaultRoot() throws IOException {
        Path file = tmp.resolve("botmaker-config.json");
        Files.writeString(file, """
                {
                  "lastOpenedProject" : "MyBot",
                  "recentProjects" : [ { "name" : "MyBot", "lastOpened" : "2026-09-01T10:00:00" } ]
                }
                """);

        ProjectPreferences read = ProjectPreferences.read(file);
        Path expected = com.botmaker.studio.config.Constants.PROJECTS_ROOT.resolve("MyBot");
        assertEquals(expected, read.lastOpenedDirectory());
        assertEquals(expected, read.getRecentProjects().getFirst().directory());
    }

    @Test
    void twoProjectsWithOneNameAreTwoEntries() {
        ProjectPreferences prefs = new ProjectPreferences();
        prefs.recordOpened(tmp.resolve("a/Bot"));
        prefs.recordOpened(tmp.resolve("b/Bot"));
        prefs.recordOpened(tmp.resolve("a/Bot"));
        assertEquals(List.of(tmp.resolve("a/Bot"), tmp.resolve("b/Bot")),
                prefs.getRecentProjects().stream().map(ProjectPreferences.ProjectEntry::directory).toList());
    }

    @Test
    void forgettingTheLastProjectClearsIt() {
        ProjectPreferences prefs = new ProjectPreferences();
        prefs.recordOpened(tmp.resolve("repos/Bot"));
        prefs.forgetRecent(tmp.resolve("repos/Bot"));
        assertTrue(prefs.getRecentProjects().isEmpty());
        assertNull(prefs.lastOpenedDirectory());
    }

    /** The welcome list's date sort reads when a project was opened, wherever it lives (2026-10-01). */
    @Test
    void eachRememberedProjectKnowsWhenItWasOpened() throws IOException {
        Path file = tmp.resolve("botmaker-config.json");
        ProjectPreferences prefs = new ProjectPreferences();
        prefs.recordOpened(tmp.resolve("repos/Bot"));
        prefs.recordOpened(tmp.resolve("Other"));
        prefs.getRecentProjects().get(1).setLastOpened("not a time");
        prefs.write(file);

        var opened = ProjectPreferences.read(file).openedTimes();
        assertEquals(List.of(tmp.resolve("Other")), List.copyOf(opened.keySet()),
                "an unreadable time is left out, so the folder's own date stands in");
    }

    // ── ProjectManager ───────────────────────────────────────────────────────

    @Test
    void theListIsWhatSitsDirectlyUnderTheRoot() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        project(root.resolve("One"), "com/one");
        Files.createDirectories(root.resolve("NotAProject"));
        ProjectManager manager = new ProjectManager(root, root.resolve(".archive"));

        assertEquals(List.of("One"), manager.listProjects().stream().map(ProjectInfo::name).toList());
    }

    @Test
    void aProjectElsewhereIsFoundOneDirectoryAtATime() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        Path outside = project(tmp.resolve("repos/Mine"), "com/mine");
        ProjectManager manager = new ProjectManager(root, root.resolve(".archive"));

        assertTrue(manager.projectAt(outside).isPresent());
        assertFalse(manager.isUnderRoot(outside));
        assertTrue(manager.projectAt(tmp.resolve("repos")).isEmpty(), "a folder without a pom is no project");
    }

    @Test
    void aProjectElsewhereIsNeverMovedIntoTheArchive() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        Path outside = project(tmp.resolve("repos/Mine"), "com/mine");
        ProjectManager manager = new ProjectManager(root, root.resolve(".archive"));

        IOException refused = assertThrows(IOException.class, () -> manager.archiveProject(outside));
        assertTrue(refused.getMessage().contains("recent list"), refused.getMessage());
        assertTrue(Files.isDirectory(outside), "the folder stays where it is");
    }

    @Test
    void aRootProjectStillArchives() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        Path inside = project(root.resolve("Old"), "com/old");
        ProjectManager manager = new ProjectManager(root, root.resolve(".archive"));

        manager.archiveProject(inside);

        assertFalse(Files.exists(inside));
        assertEquals(List.of("Old"), manager.listArchivedProjects().stream().map(ProjectInfo::name).toList());
    }

    /** The same bot got and archived twice (2026-10-25): both kept, the second as " (2)", restored as itself. */
    @Test
    void aNameAlreadyArchivedGetsANumberAndRestoresWithout() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        Path archive = root.resolve(".archive");
        ProjectManager manager = new ProjectManager(root, archive);
        manager.archiveProject(project(root.resolve("Firestone"), "com/firestone"));

        Path second = manager.archiveProject(project(root.resolve("Firestone"), "com/firestone"));

        assertEquals(archive.resolve("Firestone (2)"), second);
        assertEquals(List.of("Firestone", "Firestone (2)"), manager.listArchivedProjects().stream()
                .map(ProjectInfo::name).sorted().toList());
        manager.deleteProject("Firestone");
        manager.restoreProject("Firestone (2)");
        assertTrue(Files.isDirectory(root.resolve("Firestone")), "restored under the name it was archived from");
        assertFalse(Files.exists(archive), "no empty archive folder is left");
    }

    @Test
    void aNumberTheUserGaveIsKeptOnRestore() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        ProjectManager manager = new ProjectManager(root, root.resolve(".archive"));
        manager.archiveProject(project(root.resolve("Bot (2)"), "com/bot"));

        manager.restoreProject("Bot (2)");

        assertTrue(Files.isDirectory(root.resolve("Bot (2)")));
        assertFalse(Files.exists(root.resolve("Bot")));
    }

    @Test
    void aFailedArchiveSaysWhyInASentence() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        Path inside = project(root.resolve("Old"), "com/old");
        Files.writeString(root.resolve("blocker"), "");
        ProjectManager manager = new ProjectManager(root, root.resolve("blocker/.archive"));

        IOException refused = assertThrows(IOException.class, () -> manager.archiveProject(inside));
        assertTrue(Files.isDirectory(inside), "nothing moved");
        assertTrue(refused.getMessage().startsWith("“Old” could not be moved into the archive"),
                refused.getMessage());
    }

    /**
     * Folders Studio keeps that are gone leave the recents (2026-10-25: 7 of 10 pointed nowhere); a gone folder
     * elsewhere stays, so its card says so.
     */
    @Test
    void goneFoldersStudioKeepsArePruned() throws IOException {
        Path root = tmp.resolve("BotMakerProjects");
        Path kept = project(root.resolve("Kept"), "com/kept");
        ProjectPreferences prefs = new ProjectPreferences();
        prefs.recordOpened(root.resolve("Archived"));
        prefs.recordOpened(tmp.resolve("bots/LiQiyeDev/Firestone/v1.0.2"));
        prefs.recordOpened(tmp.resolve("usb/Bot"));
        prefs.recordOpened(kept);

        prefs.prune(List.of(root, tmp.resolve("bots")));

        assertEquals(List.of(kept, tmp.resolve("usb/Bot")),
                prefs.getRecentProjects().stream().map(ProjectPreferences.ProjectEntry::directory).toList());
    }
}
