package com.botmaker.studio.project.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectVcsTest {

    @Test
    void initCreatesRepoWithInitialCommitAndGitignore(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(dir);
        assertFalse(vcs.isRepo());

        vcs.init();

        assertTrue(vcs.isRepo());
        assertTrue(Files.exists(dir.resolve(".gitignore")));
        List<ProjectVcs.CommitInfo> history = vcs.history();
        assertEquals(1, history.size());
        assertEquals("Initial commit", history.get(0).message());
    }

    @Test
    void commitRecordsChangesAndNoOpsWhenClean(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.init();

        Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
        assertTrue(vcs.checkpoint(VersionOrigin.SAVE, "add field") != null, "a real change should produce a commit");
        assertEquals(2, vcs.history().size());

        assertNull(vcs.checkpoint(VersionOrigin.AUTO, "nothing changed"), "a clean tree should not create a commit");
        assertEquals(2, vcs.history().size());
    }

    @Test
    void everyVersionStudioWritesSaysWhoWroteIt(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        String first = vcs.history().get(0).sha();
        Files.writeString(dir.resolve("Bot.java"), "v2");
        vcs.checkpoint(VersionOrigin.SAVE, "Faster mining");
        Files.writeString(dir.resolve("Bot.java"), "v3");
        vcs.checkpoint(VersionOrigin.AUTO, "");

        List<ProjectVcs.CommitInfo> history = vcs.history();
        assertEquals(VersionOrigin.AUTO, history.get(0).origin());
        assertEquals("Automatic", history.get(0).message(), "a blank label reads as the origin's name");
        assertEquals(VersionOrigin.SAVE, history.get(1).origin());
        assertEquals("Faster mining", history.get(1).message(), "the trailer is not part of the first line");
        assertEquals(VersionOrigin.CREATE, history.get(2).origin());

        Files.writeString(dir.resolve("Bot.java"), "v4, unsaved");
        vcs.restoreTo(first);
        history = vcs.history();
        assertEquals(VersionOrigin.RESTORE, history.get(0).origin());
        assertEquals(VersionOrigin.SAFETY, history.get(1).origin(), "the unsaved v4 is kept before the restore");
    }

    @Test
    void aVersionSaysWhichFilesItChangedAndHow(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "one\n");
        Files.writeString(dir.resolve("Old.java"), "old\n");
        vcs.init();
        assertEquals(VcsFileStatus.ADDED, vcs.changes(vcs.history().getFirst().sha()).get("Bot.java"),
                "the first version adds everything");

        Files.writeString(dir.resolve("Bot.java"), "two\n");
        Files.delete(dir.resolve("Old.java"));
        Files.writeString(dir.resolve("New.java"), "new\n");
        String sha = vcs.checkpoint(VersionOrigin.SAVE, "second");

        var changes = vcs.changes(sha);
        assertEquals(VcsFileStatus.MODIFIED, changes.get("Bot.java"));
        assertEquals(VcsFileStatus.DELETED, changes.get("Old.java"));
        assertEquals(VcsFileStatus.ADDED, changes.get("New.java"));
        String diff = vcs.diff(sha, "Bot.java");
        assertTrue(diff.contains("-one") && diff.contains("+two"), diff);
        assertFalse(diff.contains("New.java"), "only the path asked for");
    }

    @Test
    void namingAVersionNeverRewritesIt(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        Files.writeString(dir.resolve("Bot.java"), "v2");
        vcs.checkpoint(VersionOrigin.AUTO, "Run");
        Files.writeString(dir.resolve("Bot.java"), "v3");
        vcs.checkpoint(VersionOrigin.AUTO, "Run");
        List<ProjectVcs.CommitInfo> before = vcs.history();
        String middle = before.get(1).sha();

        vcs.name(middle, "It worked here");

        List<ProjectVcs.CommitInfo> after = vcs.history();
        assertEquals(before.stream().map(ProjectVcs.CommitInfo::sha).toList(),
                after.stream().map(ProjectVcs.CommitInfo::sha).toList(), "same commits, same ids");
        assertEquals("It worked here", after.get(1).title());
        assertTrue(after.get(1).milestone());
        assertEquals("Run", after.get(0).title());
        assertTrue(vcs.status().isClean(), "a name is not a change to the project");

        vcs.name(middle, " ");
        assertEquals("Run", vcs.history().get(1).title(), "a blank name removes it");
    }

    @Test
    void ensureInitializedMigratesExistingProject(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.ensureInitialized();
        assertTrue(vcs.isRepo());
        assertEquals(1, vcs.history().size());
    }

    @Test
    void tagsAreSurfacedInHistory(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.init();
        vcs.tagPrivate("v0.0.1");
        vcs.tagPublic("v1.0.0");

        List<String> tags = vcs.history().get(0).tags();
        assertTrue(tags.contains("v0.0.1"));
        assertTrue(tags.contains("v1.0.0"));
    }

    @Test
    void statusBucketsWorkingTreeChanges(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        assertTrue(vcs.status().isClean(), "a freshly committed tree is clean");

        Files.writeString(dir.resolve("Bot.java"), "v2");           // modify a tracked file
        Files.writeString(dir.resolve("New.java"), "brand new");    // untracked

        ProjectVcs.FileStatus status = vcs.status();
        assertFalse(status.isClean());
        assertTrue(status.modified().contains("Bot.java"), "modified: " + status.modified());
        assertTrue(status.untracked().contains("New.java"), "untracked: " + status.untracked());
        assertEquals(VcsFileStatus.MODIFIED, status.labelled().get("Bot.java"));
        assertEquals(VcsFileStatus.NEW, status.labelled().get("New.java"));
    }

    @Test
    void diffShowsTrackedFileChangeAndDiscardReverts(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "original\n");
        vcs.init();

        Files.writeString(dir.resolve("Bot.java"), "changed\n");
        String diff = vcs.diff("Bot.java");
        assertTrue(diff.contains("-original"), "diff should show the removed line:\n" + diff);
        assertTrue(diff.contains("+changed"), "diff should show the added line:\n" + diff);

        vcs.discard("Bot.java");
        assertEquals("original\n", Files.readString(dir.resolve("Bot.java")));
        assertTrue(vcs.status().isClean(), "discarding the only change makes the tree clean again");
    }

    @Test
    void restoreToRewindsContentAsNewCommitKeepingHistory(@TempDir Path dir) throws IOException {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        String firstSha = vcs.history().get(0).sha();

        Files.writeString(dir.resolve("Bot.java"), "v2");
        Files.writeString(dir.resolve("New.java"), "added later");
        vcs.checkpoint(VersionOrigin.SAVE, "v2 + new file");
        assertEquals(2, vcs.history().size());

        vcs.restoreTo(firstSha);

        // Working tree matches the first commit's content again…
        assertEquals("v1", Files.readString(dir.resolve("Bot.java")));
        assertFalse(Files.exists(dir.resolve("New.java")), "file added after the target should be gone");
        // …and history grew rather than shrank (nothing lost).
        List<ProjectVcs.CommitInfo> history = vcs.history();
        assertEquals(3, history.size());
        assertTrue(history.get(0).message().startsWith("Roll back to"));
    }

    /**
     * A submodule's {@code .git} is a file naming the repository elsewhere. Studio read it as "no history" and
     * ran {@code git init} over it, which failed creating {@code .git/} where the file sat.
     */
    @Test
    void aGitFileIsARepositoryNotAnInvitationToInit(@TempDir Path root) throws IOException {
        Path project = submoduleCheckout(root);
        ProjectVcs vcs = new ProjectVcs(project);

        assertTrue(vcs.isRepo());
        assertEquals(1, vcs.history().size(), "the existing history, not a fresh one");

        Files.writeString(project.resolve("Bot.java"), "class Bot { int a; }");
        assertTrue(vcs.checkpoint(VersionOrigin.SAVE, "add field") != null);
        assertEquals(2, vcs.history().size());
        assertTrue(Files.isRegularFile(project.resolve(".git")), "the gitfile is left as it was");
        assertTrue(Files.readString(root.resolve("modules/bot/info/exclude")).contains("/.botmaker/"),
                "Studio's state is excluded in the real repository directory");
    }

    /**
     * In a submodule Studio takes no version nobody asked for: a "Run" version swept an old Studio's leftover
     * files into the gamebot template's {@code main}, one push from published (2026-10-01).
     */
    @Test
    void aSubmoduleGetsNoAutomaticVersionButKeepsSavedOnes(@TempDir Path root) throws IOException {
        Path project = submoduleCheckout(root);
        ProjectVcs vcs = new ProjectVcs(project);
        Files.writeString(project.resolve("Bot.java"), "class Bot { int ran; }");

        Checkpoints.take(project, VersionOrigin.AUTO, "Run");
        assertEquals(1, vcs.history().size(), "no Run version in a submodule");

        assertTrue(vcs.checkpoint(VersionOrigin.SAVE, "mine") != null, "a version the user saves is written");
        assertEquals(2, vcs.history().size());
    }

    /** An old Studio's {@code botmaker-project.properties} left on disk stays out of every version. */
    @Test
    void anOldStudiosPropertiesFileIsNeverSweptIntoAVersion(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.init();
        Path stale = dir.resolve("src/main/resources/botmaker-project.properties");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "vision.confidence=0.8\n");

        assertNull(vcs.checkpoint(VersionOrigin.AUTO, "Run"), "nothing else changed, so nothing to version");
        assertTrue(Files.exists(stale), "excluded, never deleted");
    }

    @Test
    void aPushRefusedByGitHubSaysWhoGrantsIt() {
        String said = ProjectVcs.explainRefusal("https://github.com/BotMakerDev/botmaker-gamebot.git",
                "Push failed: https://github.com/BotMakerDev/botmaker-gamebot.git: git-receive-pack not permitted");
        assertTrue(said.startsWith("GitHub refused the push to BotMakerDev/botmaker-gamebot."), said);
        assertTrue(said.contains("organizations/BotMakerDev/settings/oauth_application_policy"), said);
        assertEquals("Push failed: timeout", ProjectVcs.explainRefusal("https://github.com/a/b.git", "Push failed: timeout"));
    }

    /** The Versions tab shows a failure's deepest cause, so the explanation must be the deepest. */
    @Test
    void anExplainedRefusalIsTheMessageTheTabShows() {
        String url = "https://github.com/BotMakerDev/botmaker-gamebot.git";
        Exception jgit = new Exception(url + ": git-receive-pack not permitted on '" + url + "'");
        Throwable shown = new RuntimeException("wrapped", ProjectVcs.pushFailure(url, jgit));
        while (shown.getCause() != null) shown = shown.getCause();
        assertTrue(shown.getMessage().startsWith("GitHub refused the push to BotMakerDev/botmaker-gamebot."),
                shown.getMessage());

        IOException other = ProjectVcs.pushFailure(url, new Exception("timeout"));
        assertEquals("Push failed: timeout", other.getMessage());
        assertTrue(other.getCause() != null, "an unexplained failure keeps its cause");
    }

    /** {@code root/Project} with its repository moved to {@code root/modules/bot}, as git submodules lay it out. */
    static Path submoduleCheckout(Path root) throws IOException {
        Path project = root.resolve("Project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Bot.java"), "class Bot {}");
        new ProjectVcs(project).init();
        Path modules = Files.createDirectories(root.resolve("modules"));
        Files.move(project.resolve(".git"), modules.resolve("bot"));
        Files.writeString(project.resolve(".git"), "gitdir: ../modules/bot\n");
        return project;
    }
}
