package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clone, attach and push against local repositories standing in for GitHub — JGit's {@code file://} transport,
 * never the network ({@code 39} §12). The token is a dummy: the file transport asks for none.
 */
class RemotesTest {

    private static final String TOKEN = "unused-by-file-transport";

    /** An author's repository: v1.0.0 tagged, then one more commit on its main. */
    private static Path author(Path root) throws Exception {
        Path dir = root.resolve("author");
        Files.createDirectories(dir);
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(dir.resolve("Bot.java"), "class Bot { int v = 1; }");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("one").setAuthor("alice", "a@x").setCommitter("alice", "a@x").call();
            git.tag().setName("v1.0.0").setMessage("release").call();
            Files.writeString(dir.resolve("Bot.java"), "class Bot { int v = 2; }");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("two").setAuthor("alice", "a@x").setCommitter("alice", "a@x").call();
        }
        return dir;
    }

    private static String url(Path dir) {
        return dir.toUri().toString();
    }

    private static ObjectId commitOf(Path repo, String rev) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().resolve(rev + "^{commit}");
        }
    }

    @Test
    void anInstallIsACloneWithMainAtTheTag(@TempDir Path root) throws Exception {
        Path author = author(root);
        Path dest = root.resolve("Installed");
        ProjectVcs.cloneAt(url(author), "v1.0.0", dest, null);

        ProjectVcs vcs = new ProjectVcs(dest);
        assertEquals("main", vcs.branch());
        assertEquals(commitOf(author, "v1.0.0"), commitOf(dest, "HEAD"));
        assertEquals("class Bot { int v = 1; }", Files.readString(dest.resolve("Bot.java")));
        assertEquals(url(author), vcs.remoteUrl(Remote.ORIGINAL));
        assertTrue(Files.exists(dest.resolve(".gitignore")));
        assertTrue(vcs.remoteTags(Remote.ORIGINAL, null).contains("v1.0.0"));
    }

    @Test
    void aZipInstallIsAttachedWithTheUsersTreeUnchanged(@TempDir Path root) throws Exception {
        Path author = author(root);
        Path project = root.resolve("Zipped");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Bot.java"), "class Bot { int v = 1; }");
        ProjectVcs vcs = new ProjectVcs(project);
        vcs.init();
        Files.writeString(project.resolve("Bot.java"), "class Bot { int v = 1; int mine; }");
        vcs.checkpoint(VersionOrigin.SAVE, "my edit");
        ObjectId before = commitOf(project, "HEAD");

        assertTrue(vcs.attach(url(author), "v1.0.0", "Linked to alice/miner v1.0.0", null));

        try (Git git = Git.open(project.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit merge = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(2, merge.getParentCount());
            assertEquals(before, merge.getParent(0).getId());
            assertEquals(commitOf(author, "v1.0.0"), merge.getParent(1).getId());
            assertEquals(walk.parseCommit(before).getTree(), merge.getTree());
            assertEquals(VersionOrigin.INSTALL, VersionOrigin.of(merge.getFullMessage()));
        }
        assertEquals("class Bot { int v = 1; int mine; }", Files.readString(project.resolve("Bot.java")));
        assertTrue(vcs.status().isClean());
        assertFalse(vcs.attach(url(author), "v1.0.0", "again", null), "attached once");
    }

    @Test
    void saveToMyCopyPushesAndCountsWhatIsNotThere(@TempDir Path root) throws Exception {
        Path mine = root.resolve("mine.git");
        Git.init().setBare(true).setDirectory(mine.toFile()).call().close();
        Path project = root.resolve("Project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(project);
        vcs.init();
        vcs.name(vcs.history().getFirst().sha(), "First");
        vcs.setRemote(Remote.MINE, url(mine));
        String branch = vcs.branch();

        assertEquals(-1, vcs.notIn(Remote.MINE, branch), "never pushed");
        vcs.push(Remote.MINE, branch, TOKEN);
        assertEquals(0, vcs.notIn(Remote.MINE, branch));
        assertEquals(commitOf(project, "HEAD"), commitOf(mine, "refs/heads/" + branch));
        try (Git bare = Git.open(mine.toFile())) {
            assertNotNull(bare.getRepository().exactRef(ProjectVcs.NAMES), "names travel with the versions");
        }

        Files.writeString(project.resolve("Bot.java"), "class Bot { int a; }");
        vcs.checkpoint(VersionOrigin.SAVE, "more");
        assertEquals(1, vcs.notIn(Remote.MINE, branch));

        vcs.push(Remote.MINE, "studio/" + branch, TOKEN);
        assertEquals(0, vcs.notIn(Remote.MINE, "studio/" + branch), "a fork's line lives beside its main");
    }

    @Test
    void theOldBackupRemoteBecomesMyCopy(@TempDir Path root) throws Exception {
        Path project = root.resolve("Project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(project);
        vcs.init();
        try (Git git = Git.open(project.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", "https://github.com/bob/backup.git");
            config.save();
        }

        assertTrue(vcs.adoptLegacyBackup());
        assertEquals("https://github.com/bob/backup.git", vcs.remoteUrl(Remote.MINE));
        try (Git git = Git.open(project.toFile())) {
            assertNull(git.getRepository().getConfig().getString("remote", "origin", "url"));
        }
        assertFalse(vcs.adoptLegacyBackup(), "idempotent");
    }
}
