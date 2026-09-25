package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file's content on both sides of a change — what a version did to it, or what the unsaved edits did to it —
 * as bytes, for {@link BlockDiff} and the Versions tab's cards ({@code docs/refactor/39-versions.md} §5). It
 * reads git objects and the working tree and changes neither.
 */
public final class VersionReader {

    private final Path projectDir;
    private final ProjectVcs vcs;

    public VersionReader(Path projectDir) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.vcs = new ProjectVcs(this.projectDir);
    }

    /**
     * One file's two sides; a side is null where the file does not exist.
     *
     * @param path    the file's path after the change (before it, when the change deleted it)
     * @param oldPath the path before, when the change renamed it; else {@code path}
     */
    public record Sides(String path, String oldPath, byte[] before, byte[] after) {

        public String beforeText() {
            return before == null ? null : new String(before, StandardCharsets.UTF_8);
        }

        public String afterText() {
            return after == null ? null : new String(after, StandardCharsets.UTF_8);
        }

        public boolean renamed() {
            return !path.equals(oldPath);
        }
    }

    /** What version {@code sha} did to {@code path}, against its first parent; a rename is followed back. */
    public Sides version(String sha, String path) throws IOException {
        try (Git git = vcs.open(); DiffFormatter fmt = new DiffFormatter(OutputStream.nullOutputStream())) {
            Repository repo = git.getRepository();
            String oldPath = path;
            for (DiffEntry e : ProjectVcs.entries(repo, fmt, sha, null)) {
                if (path.equals(e.getNewPath()) && e.getChangeType() != DiffEntry.ChangeType.DELETE) {
                    oldPath = e.getChangeType() == DiffEntry.ChangeType.ADD ? path : e.getOldPath();
                    break;
                }
            }
            try (RevWalk walk = new RevWalk(repo)) {
                ObjectId id = repo.resolve(sha);
                if (id == null) throw new IOException("Unknown version: " + sha);
                RevCommit c = walk.parseCommit(id);
                byte[] after = read(repo, c, path);
                byte[] before = c.getParentCount() == 0 ? null : read(repo, walk.parseCommit(c.getParent(0)), oldPath);
                return new Sides(path, oldPath, before, after);
            }
        }
    }

    /** What the unsaved edits did to {@code path}: {@code HEAD} against the working tree. */
    public Sides unsaved(String path) throws IOException {
        byte[] before = null;
        try (Git git = vcs.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head != null) before = read(git.getRepository(), walk.parseCommit(head), path);
        }
        Path file = projectDir.resolve(path);
        return new Sides(path, path, before, Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
    }

    /** {@code path} as version {@code sha} has it, or null when that version has no such file. */
    public byte[] at(String sha, String path) throws IOException {
        try (Git git = vcs.open(); RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId id = git.getRepository().resolve(sha);
            if (id == null) throw new IOException("Unknown version: " + sha);
            return read(git.getRepository(), walk.parseCommit(id), path);
        }
    }

    /** A unified text diff of two contents of {@code path}; either may be null (absent). */
    public static String unified(String path, byte[] before, byte[] after) {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out)) {
            org.eclipse.jgit.diff.RawText a = new org.eclipse.jgit.diff.RawText(before == null ? new byte[0] : before);
            org.eclipse.jgit.diff.RawText b = new org.eclipse.jgit.diff.RawText(after == null ? new byte[0] : after);
            var edits = org.eclipse.jgit.diff.DiffAlgorithm
                    .getAlgorithm(org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                    .diff(org.eclipse.jgit.diff.RawTextComparator.DEFAULT, a, b);
            out.write(("--- " + path + "\n+++ " + path + "\n").getBytes(StandardCharsets.UTF_8));
            fmt.format(edits, a, b);
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Could not compare " + path + ": " + e.getMessage();
        }
    }

    private static byte[] read(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
            return tw == null ? null : repo.open(tw.getObjectId(0)).getBytes();
        }
    }
}
