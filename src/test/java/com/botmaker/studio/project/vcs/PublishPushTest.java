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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A publish's push step ({@code 39} §8) against local repositories standing in for GitHub: the version is
 * tagged and pushed to {@code mine}, a repository Studio once published by snapshot is joined rather than
 * overwritten, and one that is merely ahead refuses.
 */
class PublishPushTest {

    private static final String TOKEN = "unused-by-file-transport";

    private static Path bare(Path root, String name) throws Exception {
        Path dir = root.resolve(name + ".git");
        Git.init().setBare(true).setDirectory(dir.toFile()).setInitialBranch("main").call().close();
        return dir;
    }

    private static ProjectVcs project(Path root, Path mine) throws Exception {
        Path dir = root.resolve("Bot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { }");
        ProjectVcs vcs = new ProjectVcs(dir, "alice", "alice@users.noreply.github.com");
        vcs.init();
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int v = 1; }");
        vcs.checkpoint(VersionOrigin.PUBLISH, "1.0.0 published");
        vcs.setRemote(Remote.MINE, mine.toUri().toString());
        return vcs;
    }

    private static ObjectId resolve(Path repo, String rev) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().resolve(rev);
        }
    }

    @Test
    void aFirstPublishTagsAndPushesTheSavedVersion(@TempDir Path root) throws Exception {
        Path mine = bare(root, "mine");
        ProjectVcs vcs = project(root, mine);

        assertFalse(vcs.joinUnrelated(Remote.MINE, "main", "join", TOKEN), "an empty repository has nothing to join");
        String tagged = vcs.tagHead("1.0.0", "Published Bot 1.0.0");
        vcs.push(Remote.MINE, "main", TOKEN);

        assertEquals(resolve(root.resolve("Bot"), "HEAD").name(), tagged);
        assertEquals(tagged, resolve(mine, "refs/heads/main").name());
        assertEquals(tagged, resolve(mine, "refs/tags/1.0.0^{commit}").name());
        assertEquals(0, vcs.notIn(Remote.MINE, "main"));
    }

    @Test
    void aTagMadeByAnEarlierTryIsKept(@TempDir Path root) throws Exception {
        ProjectVcs vcs = project(root, bare(root, "mine"));
        String first = vcs.tagHead("1.0.0", "Published Bot 1.0.0");
        Files.writeString(root.resolve("Bot/Bot.java"), "class Bot { int v = 2; }");
        vcs.checkpoint(VersionOrigin.AUTO, "before a run");

        assertEquals(first, vcs.tagHead("1.0.0", "Published Bot 1.0.0"));
    }

    @Test
    void aSnapshotPublishedRepositoryIsJoinedWithThisComputersFiles(@TempDir Path root) throws Exception {
        Path mine = bare(root, "mine");
        // What the Git Data API publish left: a history of its own, built on GitHub from a snapshot.
        Path old = root.resolve("old");
        try (Git git = Git.cloneRepository().setURI(mine.toUri().toString()).setDirectory(old.toFile()).call()) {
            Files.writeString(old.resolve("Bot.java"), "class Bot { int old; }");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Publish Bot 0.9.0 from BotMaker Studio")
                    .setAuthor("alice", "a@x").setCommitter("alice", "a@x").call();
            // A clone of an empty repository has no branch to track; name the one GitHub had.
            git.push().setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call();
        }
        ProjectVcs vcs = project(root, mine);
        Path dir = root.resolve("Bot");
        ObjectId before = resolve(dir, "HEAD");
        ObjectId theirs = resolve(mine, "refs/heads/main");

        assertThrows(IOException.class, () -> vcs.push(Remote.MINE, "main", TOKEN), "unrelated, so not a fast-forward");
        assertTrue(vcs.joinUnrelated(Remote.MINE, "main", "Joined the versions already on GitHub", TOKEN));
        vcs.push(Remote.MINE, "main", TOKEN);

        try (Git git = Git.open(dir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(2, head.getParentCount());
            assertEquals(before, head.getParent(0));
            assertEquals(theirs, head.getParent(1));
            assertEquals(walk.parseCommit(before).getTree(), head.getTree(), "no file moved");
            assertEquals(VersionOrigin.PUBLISH, VersionOrigin.of(head.getFullMessage()));
        }
        assertEquals("class Bot { int v = 1; }", Files.readString(dir.resolve("Bot.java")));
        assertFalse(vcs.joinUnrelated(Remote.MINE, "main", "again", TOKEN), "joined once");
    }

    @Test
    void aCopyThatIsAheadIsNeitherJoinedNorOverwritten(@TempDir Path root) throws Exception {
        Path mine = bare(root, "mine");
        ProjectVcs vcs = project(root, mine);
        vcs.push(Remote.MINE, "main", TOKEN);
        // Another computer pushed a version this one does not have.
        Path other = root.resolve("other");
        try (Git git = Git.cloneRepository().setURI(mine.toUri().toString()).setDirectory(other.toFile()).call()) {
            Files.writeString(other.resolve("Other.java"), "class Other { }");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("elsewhere").setAuthor("alice", "a@x").setCommitter("alice", "a@x").call();
            git.push().call();
        }
        Files.writeString(root.resolve("Bot/Bot.java"), "class Bot { int v = 3; }");
        vcs.checkpoint(VersionOrigin.PUBLISH, "1.1.0 published");

        assertFalse(vcs.joinUnrelated(Remote.MINE, "main", "join", TOKEN));
        IOException refused = assertThrows(IOException.class, () -> vcs.push(Remote.MINE, "main", TOKEN));
        assertTrue(refused.getMessage().contains("versions this computer doesn't"), refused.getMessage());
    }
}
