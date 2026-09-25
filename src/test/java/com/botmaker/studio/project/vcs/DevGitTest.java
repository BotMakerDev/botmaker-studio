package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Dev view's git ({@code 39} §9): branches, a branch merge through the conflict path, remotes. */
class DevGitTest {

    private static ProjectVcs project(Path root) throws Exception {
        Path dir = root.resolve("Bot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {\n    int a = 1;\n}\n");
        ProjectVcs vcs = new ProjectVcs(dir, "alice", "alice@users.noreply.github.com");
        vcs.init();
        vcs.checkpoint(VersionOrigin.SAVE, "start");
        return vcs;
    }

    private static void edit(Path root, String content) throws IOException {
        Files.writeString(root.resolve("Bot/Bot.java"), content);
    }

    @Test
    void aBranchIsCreatedSwitchedToAndMergedBack(@TempDir Path root) throws Exception {
        ProjectVcs vcs = project(root);
        String main = vcs.branch();
        vcs.createBranch("experiment");
        vcs.switchTo("experiment");
        assertEquals("experiment", vcs.branch());
        edit(root, "class Bot {\n    int a = 2;\n}\n");
        vcs.checkpoint(VersionOrigin.SAVE, "try two");

        vcs.switchTo(main);
        assertEquals("class Bot {\n    int a = 1;\n}\n", Files.readString(root.resolve("Bot/Bot.java")));
        assertEquals(List.of("experiment", main).stream().sorted().toList(), vcs.branches());

        ProjectVcs.Merge merge = vcs.mergeRef("refs/heads/experiment");
        assertTrue(!merge.conflicted() && !merge.upToDate());
        vcs.finishMerge(VersionOrigin.SAVE, "Merged experiment into " + main);
        assertEquals(VersionOrigin.SAVE, vcs.history().getFirst().origin());
        assertEquals("class Bot {\n    int a = 2;\n}\n", Files.readString(root.resolve("Bot/Bot.java")));

        vcs.deleteBranch("experiment");
        assertEquals(List.of(main), vcs.branches());
    }

    @Test
    void aConflictingBranchIsDecidedPerFile(@TempDir Path root) throws Exception {
        ProjectVcs vcs = project(root);
        String main = vcs.branch();
        vcs.createBranch("other");
        edit(root, "class Bot {\n    int a = 3;\n}\n");
        vcs.checkpoint(VersionOrigin.SAVE, "mine");
        vcs.switchTo("other");
        edit(root, "class Bot {\n    int a = 4;\n}\n");
        vcs.checkpoint(VersionOrigin.SAVE, "theirs");
        vcs.switchTo(main);

        ProjectVcs.Merge merge = vcs.mergeRef("refs/heads/other");
        assertEquals(List.of("Bot.java"), merge.conflicts());
        assertThrows(IOException.class, () -> vcs.switchTo("other"), "no switching mid-merge");
        vcs.resolve("Bot.java", ProjectVcs.Side.THEIRS);
        vcs.finishMerge(VersionOrigin.SAVE, "Merged other");
        assertEquals("class Bot {\n    int a = 4;\n}\n", Files.readString(root.resolve("Bot/Bot.java")));
    }

    @Test
    void anUnmergedOrCurrentBranchIsNotDeleted(@TempDir Path root) throws Exception {
        ProjectVcs vcs = project(root);
        String main = vcs.branch();
        vcs.createBranch("lonely");
        vcs.switchTo("lonely");
        edit(root, "class Bot {\n    int a = 9;\n}\n");
        vcs.checkpoint(VersionOrigin.SAVE, "only here");
        assertThrows(IOException.class, () -> vcs.deleteBranch("lonely"), "current");
        vcs.switchTo(main);
        IOException refused = assertThrows(IOException.class, () -> vcs.deleteBranch("lonely"));
        assertTrue(refused.getMessage().contains("merge it first"), refused.getMessage());
        assertTrue(vcs.branches().contains("lonely"));
    }

    @Test
    void switchingNeedsASavedTree(@TempDir Path root) throws Exception {
        ProjectVcs vcs = project(root);
        vcs.createBranch("b");
        edit(root, "class Bot {\n    int a = 5;\n}\n");
        assertThrows(IOException.class, () -> vcs.switchTo("b"));
    }

    @Test
    void aRemoteSaysHowTheBranchStandsThereByTheLastFetch(@TempDir Path root) throws Exception {
        Path bare = root.resolve("backup.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).call().close();
        ProjectVcs vcs = project(root);
        vcs.setRemote(Remote.MINE, bare.toUri().toString());

        ProjectVcs.RemoteInfo before = vcs.remotes().getFirst();
        assertEquals("mine", before.name());
        assertEquals(-1, before.ahead(), "never fetched");

        vcs.push("mine", vcs.branch(), null, false);
        edit(root, "class Bot {\n    int a = 6;\n}\n");
        vcs.checkpoint(VersionOrigin.SAVE, "local");
        vcs.fetch("mine", null);
        ProjectVcs.RemoteInfo after = vcs.remotes().getFirst();
        assertEquals(1, after.ahead());
        assertEquals(0, after.behind());
    }

    @Test
    void aRemoteIsHttpsWithNoCredentialsInIt(@TempDir Path root) throws Exception {
        assertNull(ProjectVcs.remoteProblem("backup", "https://example.org/me/bot.git"));
        assertNotNull(ProjectVcs.remoteProblem("backup", "http://example.org/me/bot.git"));
        assertNotNull(ProjectVcs.remoteProblem("backup", "git@github.com:me/bot.git"));
        assertNotNull(ProjectVcs.remoteProblem("backup", "https://me:ghp_secret@github.com/me/bot.git"));
        assertNotNull(ProjectVcs.remoteProblem("bad name", "https://example.org/me/bot.git"));

        ProjectVcs vcs = project(root);
        vcs.addRemote("backup", "https://example.org/me/bot.git");
        assertEquals("https://example.org/me/bot.git", vcs.remoteUrl("backup"));
        assertThrows(IOException.class, () -> vcs.addRemote("backup", "https://example.org/other.git"));
    }

    @Test
    void theGitHubTokenGoesToGitHubOnly() {
        assertEquals("t", ProjectVcs.tokenFor("https://github.com/me/bot.git", "t"));
        assertEquals("t", ProjectVcs.tokenFor("HTTPS://GitHub.com/me/bot", "t"));
        assertNull(ProjectVcs.tokenFor("https://gitlab.com/me/bot.git", "t"));
        assertNull(ProjectVcs.tokenFor("https://github.com.evil.example/me/bot.git", "t"));
        assertNull(ProjectVcs.tokenFor("file:///tmp/bot.git", "t"));
        assertNull(ProjectVcs.tokenFor("https://github.com/me/bot.git", null));
    }

    @Test
    void aMergeCommitHasBothParents(@TempDir Path root) throws Exception {
        ProjectVcs vcs = project(root);
        String main = vcs.branch();
        vcs.createBranch("side");
        vcs.switchTo("side");
        Files.writeString(root.resolve("Bot/Other.java"), "class Other { }");
        vcs.checkpoint(VersionOrigin.SAVE, "side work");
        vcs.switchTo(main);
        vcs.mergeRef("refs/heads/side");
        vcs.finishMerge(VersionOrigin.SAVE, "Merged side");
        try (Git git = Git.open(root.resolve("Bot").toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(2, head.getParentCount());
        }
    }
}
