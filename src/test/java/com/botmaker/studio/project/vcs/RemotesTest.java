package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 2026-10-24: a catalog release installs only while its tag is at the commit the catalog pinned. */
    @Test
    void anInstallAtAPinnedCommitRefusesATagMovedSince(@TempDir Path root) throws Exception {
        Path author = author(root);
        String approved = commitOf(author, "v1.0.0").name();
        ProjectVcs.cloneAt(url(author), "v1.0.0", approved, root.resolve("Pinned"), null);
        assertEquals(approved, commitOf(root.resolve("Pinned"), "HEAD").name());

        String moved = commitOf(author, "main").name();
        Exception refused = assertThrows(IOException.class,
                () -> ProjectVcs.cloneAt(url(author), "v1.0.0", moved, root.resolve("Moved"), null));
        assertTrue(refused.getMessage().contains("changed after the catalog approved it"), refused.getMessage());
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

    private static ProjectVcs initialized(Path root) throws Exception {
        Path project = root.resolve("Project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Bot.java"), "class Bot {}");
        ProjectVcs vcs = new ProjectVcs(project);
        vcs.init();
        return vcs;
    }

    /** A remote as git writes one, with a remote-tracking ref of its main at HEAD. */
    private static void remote(ProjectVcs vcs, String name, String url) throws Exception {
        try (Git git = vcs.open()) {
            var repo = git.getRepository();
            var config = repo.getConfig();
            config.setString("remote", name, "url", url);
            config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
            config.save();
            var ref = repo.updateRef("refs/remotes/" + name + "/main");
            ref.setNewObjectId(repo.resolve("HEAD"));
            ref.forceUpdate();
        }
    }

    private static String config(ProjectVcs vcs, String section, String sub, String key) throws Exception {
        try (Git git = vcs.open()) {
            return git.getRepository().getConfig().getString(section, sub, key);
        }
    }

    private static boolean hasRef(ProjectVcs vcs, String name) throws Exception {
        try (Git git = vcs.open()) {
            return git.getRepository().exactRef(name) != null;
        }
    }

    @Test
    void mineAndOriginalBecomeOriginAndUpstream(@TempDir Path root) throws Exception {
        ProjectVcs vcs = initialized(root);
        remote(vcs, "mine", "https://github.com/bob/Bot.git");
        remote(vcs, "original", "https://github.com/alice/Bot.git");
        try (Git git = vcs.open()) {
            var config = git.getRepository().getConfig();
            config.setString("branch", vcs.branch(), "remote", "original");
            config.save();
        }

        assertTrue(vcs.adoptRemoteNames());
        assertEquals("https://github.com/bob/Bot.git", vcs.remoteUrl("origin"));
        assertEquals("https://github.com/alice/Bot.git", vcs.remoteUrl("upstream"));
        assertNull(vcs.remoteUrl("mine"));
        assertNull(vcs.remoteUrl("original"));
        assertEquals("+refs/heads/*:refs/remotes/upstream/*", config(vcs, "remote", "upstream", "fetch"));
        assertEquals("upstream", config(vcs, "branch", vcs.branch(), "remote"), "the branch tracks it still");
        assertTrue(hasRef(vcs, "refs/remotes/origin/main"));
        assertFalse(hasRef(vcs, "refs/remotes/mine/main"));
        assertFalse(vcs.adoptRemoteNames(), "idempotent");
    }

    /** The old backup {@code origin} was always the user's own; Studio pushes to it over HTTPS with the token. */
    @Test
    void theOldBackupOriginStaysMyCopy(@TempDir Path root) throws Exception {
        ProjectVcs vcs = initialized(root);
        remote(vcs, "origin", "git@github.com:bob/backup.git");

        assertTrue(vcs.adoptRemoteNames());
        assertEquals("https://github.com/bob/backup.git", vcs.remoteUrl(Remote.MINE));
        assertTrue(hasRef(vcs, "refs/remotes/origin/main"));
        assertFalse(vcs.adoptRemoteNames(), "idempotent");
    }

    @Test
    void anotherRepositoryNamedOriginIsMovedAside(@TempDir Path root) throws Exception {
        ProjectVcs vcs = initialized(root);
        remote(vcs, "origin", "https://example.org/someone/else.git");
        remote(vcs, "mine", "https://github.com/bob/Bot.git");

        assertTrue(vcs.adoptRemoteNames());
        assertEquals("https://github.com/bob/Bot.git", vcs.remoteUrl(Remote.MINE));
        assertEquals("https://example.org/someone/else.git", vcs.remoteUrl("old-origin"));
        assertTrue(hasRef(vcs, "refs/remotes/old-origin/main"));
    }

    /**
     * A submodule's {@code origin} is pushed to by name by another tool (the umbrella's release): Studio's copy
     * of it, {@code mine}, is dropped and {@code origin} stays as it is.
     */
    @Test
    void aSubmodulesOriginStaysAsItIs(@TempDir Path root) throws Exception {
        Path project = ProjectVcsTest.submoduleCheckout(root);
        ProjectVcs vcs = new ProjectVcs(project);
        try (Git git = vcs.open()) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", "git@github.com:BotMakerDev/bot.git");
            config.setString("remote", "mine", "url", "https://github.com/BotMakerDev/bot.git");
            config.save();
        }

        assertTrue(vcs.adoptRemoteNames());
        assertEquals("git@github.com:BotMakerDev/bot.git", vcs.remoteUrl(Remote.MINE));
        assertNull(vcs.remoteUrl("mine"));
        assertFalse(vcs.adoptRemoteNames(), "idempotent");
    }

    @Test
    void aMergedLineIsDeletedOnTheRemote(@TempDir Path root) throws Exception {
        Path mine = root.resolve("mine.git");
        Git.init().setBare(true).setDirectory(mine.toFile()).call().close();
        ProjectVcs vcs = initialized(root);
        vcs.setRemote(Remote.MINE, url(mine));
        String branch = vcs.branch();
        vcs.push(Remote.MINE, "studio/" + branch, TOKEN);
        assertFalse(vcs.mergedInto(Remote.MINE, "studio/" + branch, branch), "main is not there yet");
        vcs.push(Remote.MINE, branch, TOKEN);
        vcs.fetch(Remote.MINE, TOKEN);
        assertTrue(vcs.mergedInto(Remote.MINE, "studio/" + branch, branch));

        vcs.deleteRemoteBranch(Remote.MINE, "studio/" + branch, TOKEN);
        try (Git bare = Git.open(mine.toFile())) {
            assertNull(bare.getRepository().exactRef("refs/heads/studio/" + branch));
            assertNotNull(bare.getRepository().exactRef("refs/heads/" + branch));
        }
        assertFalse(vcs.hasRemoteBranch(Remote.MINE, "studio/" + branch));
        assertThrows(IOException.class, () -> vcs.deleteRemoteBranch(Remote.MINE, "studio/" + branch, TOKEN),
                "nothing read of it any more");
    }

    /** A line another computer pushed to since this one read it is refused, never erased. */
    @Test
    void aLineThatMovedOnTheRemoteIsNotDeleted(@TempDir Path root) throws Exception {
        Path mine = root.resolve("mine.git");
        Git.init().setBare(true).setDirectory(mine.toFile()).call().close();
        ProjectVcs vcs = initialized(root);
        vcs.setRemote(Remote.MINE, url(mine));
        String line = "studio/" + vcs.branch();
        vcs.push(Remote.MINE, line, TOKEN);

        Path other = root.resolve("Other");
        try (Git git = Git.cloneRepository().setURI(url(mine)).setDirectory(other.toFile()).setBranch(line).call()) {
            Files.writeString(other.resolve("Bot.java"), "class Bot { int elsewhere; }");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("elsewhere").setAuthor("bob", "b@x").setCommitter("bob", "b@x").call();
            git.push().setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/" + line)).call();
        }

        IOException refused = assertThrows(IOException.class, () -> vcs.deleteRemoteBranch(Remote.MINE, line, TOKEN));
        assertTrue(refused.getMessage().contains("not read"), refused.getMessage());
        assertEquals(commitOf(other, "HEAD"), commitOf(mine, "refs/heads/" + line));
    }
}
