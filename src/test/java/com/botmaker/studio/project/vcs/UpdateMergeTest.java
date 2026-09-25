package com.botmaker.studio.project.vcs;

import com.botmaker.studio.project.vcs.ProjectVcs.Side;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <i>Get vX.Y</i> is a real merge ({@code 39} §7): a file only the author changed updates, a file only the user
 * changed stays, a file both changed is decided whole, and a cancel leaves the project as it was. Against a
 * local repository standing in for GitHub.
 */
class UpdateMergeTest {

    private Path author;
    private Path project;
    private ProjectVcs vcs;

    private static void commit(Git git, Path dir, String file, String content, String message) throws Exception {
        Files.writeString(dir.resolve(file), content);
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("alice", "a@x").setCommitter("alice", "a@x").call();
    }

    /** Author: v1.0.0 with A, B; then v1.1.0 changing A and the first line of B. User: a clone that edits B and adds C. */
    private void setUp(Path root, boolean userTouchesB) throws Exception {
        author = root.resolve("author");
        Files.createDirectories(author);
        try (Git git = Git.init().setDirectory(author.toFile()).setInitialBranch("main").call()) {
            Files.writeString(author.resolve("B.java"), "b1\n");
            commit(git, author, "A.java", "a1\n", "one");
            git.tag().setName("v1.0.0").setMessage("r").call();
            Files.writeString(author.resolve("B.java"), "b2 author\n");
            commit(git, author, "A.java", "a2\n", "two");
            git.tag().setName("v1.1.0").setMessage("r").call();
        }
        project = root.resolve("Mine");
        ProjectVcs.cloneAt(author.toUri().toString(), "v1.0.0", project, null);
        vcs = new ProjectVcs(project);
        vcs.checkpoint(VersionOrigin.INSTALL, "Installed v1.0.0");
        if (userTouchesB) Files.writeString(project.resolve("B.java"), "b2 mine\n");
        Files.writeString(project.resolve("C.java"), "c mine\n");
        vcs.checkpoint(VersionOrigin.SAVE, "my change");
        vcs.fetchTag(Remote.ORIGINAL, "v1.1.0", null);
    }

    @Test
    void filesOnlyOneSideChangedMergeWithoutAsking(@TempDir Path root) throws Exception {
        setUp(root, false);
        ProjectVcs.Merge merge = vcs.mergeTag("v1.1.0");
        assertFalse(merge.conflicted());
        assertTrue(vcs.merging());
        vcs.finishMerge("Updated to v1.1.0 (alice)");

        assertEquals("a2\n", Files.readString(project.resolve("A.java")));
        assertEquals("b2 author\n", Files.readString(project.resolve("B.java")));
        assertEquals("c mine\n", Files.readString(project.resolve("C.java")));
        assertFalse(vcs.merging());
        ProjectVcs.CommitInfo top = vcs.history().getFirst();
        assertEquals(VersionOrigin.UPDATE, top.origin());
        assertEquals("Updated to v1.1.0 (alice)", top.title());
        try (Git git = Git.open(project.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit c = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(2, c.getParentCount(), "a merge: the author's release is a parent");
        }
    }

    @Test
    void aFileBothChangedIsDecidedWhole(@TempDir Path root) throws Exception {
        setUp(root, true);
        ProjectVcs.Merge merge = vcs.mergeTag("v1.1.0");
        assertEquals(List.of("B.java"), merge.conflicts());
        assertEquals(List.of("B.java"), vcs.unresolved());
        assertEquals("v1.1.0", vcs.mergeRelease());
        assertThrows(IOException.class, () -> vcs.finishMerge("too soon"));
        assertThrows(IOException.class, () -> vcs.checkpoint(VersionOrigin.AUTO, "Run"),
                "no version may record conflict markers");

        vcs.resolve("B.java", Side.MINE);
        assertTrue(vcs.unresolved().isEmpty());
        vcs.finishMerge("Updated to v1.1.0 (alice)");

        assertEquals("b2 mine\n", Files.readString(project.resolve("B.java")));
        assertEquals("a2\n", Files.readString(project.resolve("A.java")));
        assertTrue(vcs.status().isClean());
    }

    @Test
    void takingTheirsTakesTheAuthorsFile(@TempDir Path root) throws Exception {
        setUp(root, true);
        vcs.mergeTag("v1.1.0");
        vcs.resolve("B.java", Side.THEIRS);
        vcs.finishMerge("Updated");
        assertEquals("b2 author\n", Files.readString(project.resolve("B.java")));
    }

    @Test
    void cancelPutsEverythingBack(@TempDir Path root) throws Exception {
        setUp(root, true);
        String head = vcs.history().getFirst().sha();
        vcs.mergeTag("v1.1.0");
        vcs.abortMerge();

        assertFalse(vcs.merging());
        assertEquals(head, vcs.history().getFirst().sha());
        assertEquals("b2 mine\n", Files.readString(project.resolve("B.java")));
        assertEquals("a1\n", Files.readString(project.resolve("A.java")));
        assertTrue(vcs.status().isClean());
    }

    @Test
    void anUnsavedTreeIsNotMerged(@TempDir Path root) throws Exception {
        setUp(root, false);
        Files.writeString(project.resolve("C.java"), "unsaved\n");
        assertThrows(IOException.class, () -> vcs.mergeTag("v1.1.0"));
    }

    @Test
    void aReleaseTheAuthorMovedIsRefused(@TempDir Path root) throws Exception {
        setUp(root, false);
        try (Git git = Git.open(author.toFile())) {
            commit(git, author, "A.java", "a3\n", "three");
            git.tag().setName("v1.1.0").setMessage("moved").setForceUpdate(true).call();
        }
        IOException refused = assertThrows(IOException.class, () -> vcs.fetchTag(Remote.ORIGINAL, "v1.1.0", null));
        assertTrue(refused.getMessage().contains("changed by its author"), refused.getMessage());
    }

    @Test
    void aReleaseThatIsGoneSaysSo(@TempDir Path root) throws Exception {
        setUp(root, false);
        assertThrows(IOException.class, () -> vcs.fetchTag(Remote.ORIGINAL, "v9.9.9", null));
    }
}
