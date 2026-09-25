package com.botmaker.studio.project.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Git history for one user project, backed by JGit (no system git binary): versions ({@link #checkpoint}),
 * names, restores, and the two remotes of {@code docs/refactor/39-versions.md} §2 — {@link Remote#MINE} and
 * {@link Remote#ORIGINAL} — with a clone ({@link #cloneAt}), a fetch, a push and the attach of a zip install
 * ({@link #attach}). Restore rewinds the working tree to an earlier commit's content as a new commit on top of
 * the current tip, so nothing is ever lost. <b>A token is passed per call and never written</b> — not to
 * {@code .git/config}, not into a remote URL.
 *
 * <p>Blocking / does file + git I/O — intended to run off the FX thread (the VCS panel wraps calls in a
 * background task).
 */
public final class ProjectVcs {

    /**
     * One entry in the project history: {@code message} is the first line, {@code origin} who wrote it (from
     * the trailer), {@code name} what the user called it afterwards (null if nothing), {@code tags} the
     * (possibly empty) list of tags pointing here.
     */
    public record CommitInfo(String sha, String shortSha, String message, VersionOrigin origin, String name,
                             String author, Instant when, List<String> tags) {

        /** What the timeline calls it: the user's name for it, else its first line. */
        public String title() {
            return name != null ? name : message;
        }

        /** A milestone somebody chose: a saved version, or any version given a name since. */
        public boolean milestone() {
            return name != null || origin == VersionOrigin.SAVE;
        }
    }

    /** Where version names live: notes, so naming a version never rewrites it (39-versions.md §4). */
    public static final String NAMES = "refs/notes/botmaker-names";

    /**
     * The working tree's changes relative to {@code HEAD}, bucketed by kind, as POSIX-style relative paths.
     * {@code added}/{@code modified}/{@code removed} are tracked changes staged or unstaged; {@code untracked}
     * is new files git isn't yet following. Empty sets when the tree is clean (or the project isn't a repo).
     */
    public record FileStatus(java.util.SortedSet<String> added, java.util.SortedSet<String> modified,
                             java.util.SortedSet<String> removed, java.util.SortedSet<String> untracked) {

        public boolean isClean() {
            return added.isEmpty() && modified.isEmpty() && removed.isEmpty() && untracked.isEmpty();
        }

        /** All changed paths, sorted, each mapped to how it differs from the last commit. */
        public java.util.SortedMap<String, VcsFileStatus> labelled() {
            java.util.SortedMap<String, VcsFileStatus> out = new java.util.TreeMap<>();
            untracked.forEach(p -> out.put(p, VcsFileStatus.NEW));
            added.forEach(p -> out.put(p, VcsFileStatus.ADDED));
            modified.forEach(p -> out.put(p, VcsFileStatus.MODIFIED));
            removed.forEach(p -> out.put(p, VcsFileStatus.DELETED));
            return out;
        }

        public static FileStatus empty() {
            return new FileStatus(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        }
    }

    private static final PersonIdent DEFAULT_AUTHOR =
            new PersonIdent("BotMaker Studio", "studio@botmaker.local");

    /**
     * Names git should ignore. Mirrors {@code ProjectArchive}'s publish exclusions for build output, and adds
     * the local-only editor state {@code ProjectArchive} also excludes but that additionally shouldn't clutter
     * local history: the Reader/Editor opt-in marker. (Provenance, {@code botmaker-source.json}, is deliberately
     * left tracked — it is worth versioning so a rollback keeps a bot's origin.)
     */
    private static final List<String> GITIGNORE_LINES = List.of(
            "target/", ".idea/", ".gradle/", "build/", "out/", "*.class",
            com.botmaker.studio.project.ProjectMode.MARKER);

    private final Path projectDir;
    private final PersonIdent author;

    public ProjectVcs(Path projectDir) {
        this(projectDir, DEFAULT_AUTHOR);
    }

    /** Uses {@code name}/{@code email} (e.g. the signed-in GitHub identity) as the commit author. */
    public ProjectVcs(Path projectDir, String name, String email) {
        this(projectDir, new PersonIdent(
                name == null || name.isBlank() ? DEFAULT_AUTHOR.getName() : name,
                email == null || email.isBlank() ? DEFAULT_AUTHOR.getEmailAddress() : email));
    }

    private ProjectVcs(Path projectDir, PersonIdent author) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.author = author;
    }

    /** True when the project directory already has a {@code .git} repository. */
    public boolean isRepo() {
        return Files.isDirectory(projectDir.resolve(".git"));
    }

    /**
     * Initializes a repo (with a {@code .gitignore}) and makes the initial commit. No-op if a repo already
     * exists — call this on project creation, or lazily before any other VCS action for a pre-existing project.
     */
    public void init() throws IOException {
        if (isRepo()) return;
        try (Git git = Git.init().setDirectory(projectDir.toFile()).call()) {
            writeGitignore();
            git.add().addFilepattern(".").call();
            git.commit().setAuthor(author).setCommitter(author)
                    .setMessage(VersionOrigin.CREATE.stamp("Initial commit")).call();
        } catch (Exception e) {
            throw new IOException("Failed to initialize project history: " + e.getMessage(), e);
        }
    }

    /** Lazily initializes the repo (migrating an older project) if it isn't one yet. */
    public void ensureInitialized() throws IOException {
        if (!isRepo()) init();
    }

    /**
     * Stages every change (additions, modifications, deletions of tracked files) and commits it as a version
     * of {@code origin}, the label its first line. Returns the new commit's short SHA, or {@code null} when
     * there was nothing to commit — a checkpoint of an unchanged tree is noise in the one list a user reads.
     *
     * <p>The only way Studio writes a version: the trailer is what lets the timeline tell a user's milestone
     * from the snapshots taken on their behalf ({@code docs/refactor/39-versions.md} §3).
     */
    public String checkpoint(VersionOrigin origin, String label) throws IOException {
        ensureInitialized();
        // Mid-update, "add everything" would record conflict markers as the user's decision.
        if (merging()) throw new IOException("An update is in progress — finish or cancel it in the Versions tab.");
        try (Git git = open()) {
            git.add().addFilepattern(".").call();           // new + modified files
            git.add().addFilepattern(".").setUpdate(true).call(); // deletions of tracked files
            if (git.status().call().isClean()) return null;
            String first = label == null || label.isBlank() ? origin.displayName() : label;
            RevCommit c = git.commit().setAuthor(author).setCommitter(author)
                    .setMessage(origin.stamp(first)).call();
            return c.abbreviate(7).name();
        } catch (Exception e) {
            throw new IOException("Commit failed: " + e.getMessage(), e);
        }
    }

    /** Newest-first commit history, each annotated with its name (if given one) and any tags pointing at it. */
    public List<CommitInfo> history() throws IOException {
        if (!isRepo()) return List.of();
        try (Git git = open()) {
            Map<String, List<String>> tagsByCommit = tagsByCommit(git);
            Map<String, String> names = names(git);
            List<CommitInfo> out = new ArrayList<>();
            for (RevCommit c : git.log().call()) {
                String sha = c.name();
                out.add(new CommitInfo(sha, c.abbreviate(7).name(), c.getShortMessage(),
                        VersionOrigin.of(c.getFullMessage()), names.get(sha), c.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(c.getCommitTime()), tagsByCommit.getOrDefault(sha, List.of())));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not read project history: " + e.getMessage(), e);
        }
    }

    /**
     * Names the version {@code sha} without rewriting it: the name is a git note under {@link #NAMES}, so an
     * automatic version becomes a milestone wherever it sits in the history, pushed or not. A blank name
     * removes it.
     */
    public void name(String sha, String name) throws IOException {
        try (Git git = open(); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit c = walk.parseCommit(git.getRepository().resolve(sha));
            if (name == null || name.isBlank()) {
                git.notesRemove().setNotesRef(NAMES).setObjectId(c).call();
            } else {
                git.notesAdd().setNotesRef(NAMES).setObjectId(c).setMessage(name.strip()).call();
            }
        } catch (Exception e) {
            throw new IOException("Could not name that version: " + e.getMessage(), e);
        }
    }

    /**
     * The paths version {@code sha} changed against its first parent — every file, for the first commit — with
     * how each changed. A rename reads as the new path, modified.
     */
    public java.util.SortedMap<String, VcsFileStatus> changes(String sha) throws IOException {
        java.util.SortedMap<String, VcsFileStatus> out = new java.util.TreeMap<>();
        try (Git git = open(); DiffFormatter fmt = new DiffFormatter(OutputStream.nullOutputStream())) {
            for (DiffEntry e : entries(git.getRepository(), fmt, sha, null)) {
                switch (e.getChangeType()) {
                    case ADD, COPY -> out.put(e.getNewPath(), VcsFileStatus.ADDED);
                    case DELETE -> out.put(e.getOldPath(), VcsFileStatus.DELETED);
                    default -> out.put(e.getNewPath(), VcsFileStatus.MODIFIED);
                }
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not read what " + sha + " changed: " + e.getMessage(), e);
        }
    }

    /** A unified text diff of one path as version {@code sha} changed it, against its first parent. */
    public String diff(String sha, String relativePath) throws IOException {
        try (Git git = open(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out)) {
            for (DiffEntry e : entries(git.getRepository(), fmt, sha, relativePath)) fmt.format(e);
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Could not diff " + relativePath + " in " + sha + ": " + e.getMessage(), e);
        }
    }

    static List<DiffEntry> entries(Repository repo, DiffFormatter fmt, String sha, String path)
            throws IOException {
        fmt.setRepository(repo);
        fmt.setDetectRenames(true);
        if (path != null) fmt.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path));
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            ObjectId id = repo.resolve(sha);
            if (id == null) throw new IOException("Unknown version: " + sha);
            RevCommit c = walk.parseCommit(id);
            CanonicalTreeParser after = new CanonicalTreeParser();
            after.reset(reader, c.getTree());
            if (c.getParentCount() == 0) {
                return fmt.scan(new org.eclipse.jgit.treewalk.EmptyTreeIterator(), after);
            }
            CanonicalTreeParser before = new CanonicalTreeParser();
            before.reset(reader, walk.parseCommit(c.getParent(0)).getTree());
            return fmt.scan(before, after);
        }
    }

    /** Version names by commit id, read off {@link #NAMES}. */
    private static Map<String, String> names(Git git) throws Exception {
        Map<String, String> out = new java.util.HashMap<>();
        if (git.getRepository().exactRef(NAMES) == null) return out;
        for (org.eclipse.jgit.notes.Note note : git.notesList().setNotesRef(NAMES).call()) {
            String text = new String(git.getRepository().open(note.getData()).getBytes(), StandardCharsets.UTF_8);
            if (!text.isBlank()) out.put(note.name(), text.strip());
        }
        return out;
    }

    /**
     * The working tree's changes against {@code HEAD}, bucketed for the VCS panel's changed-files tree. New
     * files added but not committed since {@code init} show up as {@code added}/{@code untracked}. Clean (and
     * empty) when the project has no repo yet.
     */
    public FileStatus status() throws IOException {
        if (!isRepo()) return FileStatus.empty();
        try (Git git = open()) {
            org.eclipse.jgit.api.Status s = git.status().call();
            java.util.SortedSet<String> added = new TreeSet<>(s.getAdded());
            added.addAll(s.getChanged());                 // staged modifications count as added-to-index
            java.util.SortedSet<String> modified = new TreeSet<>(s.getModified());
            java.util.SortedSet<String> removed = new TreeSet<>(s.getRemoved());
            removed.addAll(s.getMissing());               // tracked-but-deleted-on-disk
            java.util.SortedSet<String> untracked = new TreeSet<>(s.getUntracked());
            return new FileStatus(added, modified, removed, untracked);
        } catch (Exception e) {
            throw new IOException("Could not read project status: " + e.getMessage(), e);
        }
    }

    /**
     * A unified text diff of one working-tree file against {@code HEAD} (what committing it would record).
     * Returns an empty string when there is no difference, or a placeholder note for a binary change. The
     * path is the POSIX-style project-relative path from {@link #status()}.
     */
    public String diff(String relativePath) throws IOException {
        if (!isRepo()) return "";
        try (Git git = open(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(out)) {
            Repository repo = git.getRepository();
            fmt.setRepository(repo);
            fmt.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(relativePath));

            ObjectId head = repo.resolve("HEAD^{tree}");
            List<DiffEntry> entries;
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                if (head != null) oldTree.reset(reader, head);
                // HEAD tree (or an empty one for a repo with no commit) vs the working directory.
                entries = fmt.scan(head != null ? oldTree : new org.eclipse.jgit.treewalk.EmptyTreeIterator(),
                        new FileTreeIterator(repo));
            }
            for (DiffEntry e : entries) {
                fmt.format(e);
            }
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Could not diff " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Discards a single file's uncommitted changes, restoring it to its {@code HEAD} content ({@code git
     * checkout -- path}). A far narrower undo than {@link #restoreTo}. No-op for an untracked file (there is
     * no committed version to restore — the caller deletes it instead if desired).
     */
    public void discard(String relativePath) throws IOException {
        ensureInitialized();
        try (Git git = open()) {
            git.checkout().addPath(relativePath).call();
        } catch (Exception e) {
            throw new IOException("Could not discard " + relativePath + ": " + e.getMessage(), e);
        }
    }

    /** A private (local-only) tag — never triggers a gallery publish. */
    public void tagPrivate(String name) throws IOException {
        createTag(name, "Private tag " + name);
    }

    /**
     * A public tag — the caller (publish flow) treats this as the signal to submit to the gallery. The tag
     * itself is an ordinary annotated tag; the "public" distinction is the action taken alongside it.
     */
    public void tagPublic(String name) throws IOException {
        createTag(name, "Public release " + name);
    }

    /**
     * Safely rewinds the project to the state at {@code sha} without losing any history: any pending changes
     * are committed first (snapshot), then a new commit is created on top of the current tip whose content
     * exactly matches {@code sha}. Fully linear and reflog-safe.
     */
    public void restoreTo(String sha) throws IOException {
        ensureInitialized();
        try (Git git = open()) {
            Repository repo = git.getRepository();
            ObjectId target = repo.resolve(sha);
            if (target == null) throw new IOException("Unknown commit: " + sha);

            if (!git.status().call().isClean()) {
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setAuthor(author).setCommitter(author)
                        .setMessage(VersionOrigin.SAFETY.stamp("Snapshot before rollback")).call();
            }
            ObjectId tip = repo.resolve("HEAD");
            String shortTarget = target.abbreviate(7).name();

            // Hard-reset the working tree/index to the target, then soft-reset HEAD back to the tip so the
            // recovered content lands as one new commit — the intervening commits stay reachable in reflog.
            git.reset().setMode(ResetType.HARD).setRef(target.name()).call();
            git.reset().setMode(ResetType.SOFT).setRef(tip.name()).call();
            if (!git.status().call().isClean()) {
                git.commit().setAuthor(author).setCommitter(author)
                        .setMessage(VersionOrigin.RESTORE.stamp("Roll back to " + shortTarget)).call();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Rollback failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Updates: a real merge of the author's tag (39 §7)
    // -------------------------------------------------------------------------

    /** Whose side of a conflicting file wins. */
    public enum Side { MINE, THEIRS }

    /**
     * How {@link #mergeTag} left the project.
     *
     * @param conflicts the files both sides changed, to be decided one by one; empty unless {@link #conflicted}
     */
    public record Merge(boolean upToDate, List<String> conflicts) {

        public boolean conflicted() {
            return !conflicts.isEmpty();
        }
    }

    /**
     * Fetches the tag {@code tag} from {@code remote} without moving one this computer already has: a release
     * its author moved after the user took it is refused, with a sentence, and nothing changes.
     */
    public void fetchTag(Remote remote, String tag, String token) throws IOException {
        try (Git git = open()) {
            Repository repo = git.getRepository();
            ObjectId had = repo.resolve("refs/tags/" + tag + "^{commit}");
            var fetch = git.fetch().setRemote(remote.id())
                    .setRefSpecs(new RefSpec("refs/tags/" + tag + ":refs/tags/" + tag))
                    .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS);
            if (token != null && !token.isBlank()) fetch.setCredentialsProvider(credentials(token));
            var result = fetch.call();
            var update = result.getTrackingRefUpdate("refs/tags/" + tag);
            if (update != null && update.getResult() == org.eclipse.jgit.lib.RefUpdate.Result.REJECTED) {
                throw new IOException("The release " + tag + " was changed by its author after you took it, "
                        + "so nothing was updated.");
            }
            ObjectId now = repo.resolve("refs/tags/" + tag + "^{commit}");
            if (now == null) throw new IOException("The release " + tag + " is gone from the original.");
            if (had != null && !had.equals(now)) {
                throw new IOException("The release " + tag + " was changed by its author after you took it, "
                        + "so nothing was updated.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not fetch " + tag + ": " + e.getMessage(), e);
        }
    }

    /**
     * Merges the tag {@code tag} into the current branch, <b>without committing</b>: files only one side changed
     * are merged, and a file both changed is left conflicted for {@link #resolve}. The tree must be saved first —
     * the caller takes a version — so {@link #abortMerge} can always put it back. {@link #finishMerge} commits.
     */
    public Merge mergeTag(String tag) throws IOException {
        try (Git git = open()) {
            if (!git.status().call().isClean()) {
                throw new IOException("Save your changes as a version before updating.");
            }
            ObjectId at = git.getRepository().resolve("refs/tags/" + tag + "^{commit}");
            if (at == null) throw new IOException("The release " + tag + " is not on this computer.");
            return merge(git, at);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not update: " + e.getMessage(), e);
        }
    }

    /**
     * {@link #mergeTag}'s merge, of a branch — {@code refs/heads/…}, or {@code refs/remotes/<remote>/…} for a
     * pull — into the current one: uncommitted, conflicts left for {@link #resolve}, a saved tree required.
     */
    public Merge mergeRef(String ref) throws IOException {
        try (Git git = open()) {
            if (!git.status().call().isClean()) {
                throw new IOException("Save your changes as a version before merging.");
            }
            ObjectId at = git.getRepository().resolve(ref + "^{commit}");
            if (at == null) throw new IOException(Repository.shortenRefName(ref) + " is not on this computer.");
            return merge(git, at);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not merge: " + e.getMessage(), e);
        }
    }

    /** No fast-forward, never committed: the caller finishes it ({@link #finishMerge}) or cancels it. */
    private static Merge merge(Git git, ObjectId at) throws Exception {
        org.eclipse.jgit.api.MergeResult result = git.merge().include(at)
                .setCommit(false)
                .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF)
                .setStrategy(org.eclipse.jgit.merge.MergeStrategy.RECURSIVE)
                .call();
        return switch (result.getMergeStatus()) {
            case ALREADY_UP_TO_DATE -> new Merge(true, List.of());
            case CONFLICTING -> new Merge(false, List.copyOf(new TreeSet<>(result.getConflicts().keySet())));
            case MERGED_NOT_COMMITTED, MERGED, FAST_FORWARD, MERGED_SQUASHED, MERGED_SQUASHED_NOT_COMMITTED,
                 FAST_FORWARD_SQUASHED -> new Merge(false, List.of());
            default -> throw new IOException("The merge could not start (" + result.getMergeStatus() + ").");
        };
    }

    /** Whether an update is half done — merged, not yet committed or cancelled. */
    public boolean merging() {
        if (!isRepo()) return false;
        try (Git git = open()) {
            return git.getRepository().getRepositoryState() != org.eclipse.jgit.lib.RepositoryState.SAFE;
        } catch (Exception e) {
            return false;
        }
    }

    /** The release a half-done update is merging — its tag, else its short SHA; null when none is. */
    public String mergeRelease() throws IOException {
        try (Git git = open()) {
            List<ObjectId> heads = git.getRepository().readMergeHeads();
            if (heads == null || heads.isEmpty()) return null;
            ObjectId head = heads.getFirst();
            return tagsByCommit(git).getOrDefault(head.name(), List.of(head.abbreviate(7).name())).getFirst();
        } catch (Exception e) {
            throw new IOException("Could not read the update: " + e.getMessage(), e);
        }
    }

    /** The files of an update still to decide. */
    public List<String> unresolved() throws IOException {
        try (Git git = open()) {
            return List.copyOf(new TreeSet<>(git.status().call().getConflicting()));
        } catch (Exception e) {
            throw new IOException("Could not read the update: " + e.getMessage(), e);
        }
    }

    /**
     * Decides one conflicting file: this computer's side or the author's, whole — a file one side deleted is
     * deleted when that side wins.
     */
    public void resolve(String path, Side side) throws IOException {
        try (Git git = open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            ObjectId from = side == Side.MINE ? repo.resolve("HEAD") : repo.readMergeHeads().getFirst();
            byte[] content = null;
            try (org.eclipse.jgit.treewalk.TreeWalk tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                    repo, path, walk.parseCommit(from).getTree())) {
                if (tw != null) content = repo.open(tw.getObjectId(0)).getBytes();
            }
            Path file = projectDir.resolve(path);
            if (content == null) {
                Files.deleteIfExists(file);
                git.rm().addFilepattern(path).call();
            } else {
                Files.createDirectories(file.getParent());
                Files.write(file, content);
                git.add().addFilepattern(path).call();
            }
        } catch (Exception e) {
            throw new IOException("Could not decide " + path + ": " + e.getMessage(), e);
        }
    }

    /** Commits the update as an {@code UPDATE} version once every file is decided; returns its short SHA. */
    public String finishMerge(String label) throws IOException {
        return finishMerge(VersionOrigin.UPDATE, label);
    }

    /** {@link #finishMerge(String)} as a version of {@code origin} — a branch merged in the Dev view is a {@code SAVE}. */
    public String finishMerge(VersionOrigin origin, String label) throws IOException {
        if (!unresolved().isEmpty()) throw new IOException("Some files are still to decide.");
        try (Git git = open()) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit c = git.commit().setAuthor(author).setCommitter(author)
                    .setMessage(origin.stamp(label)).call();
            return c.abbreviate(7).name();
        } catch (Exception e) {
            throw new IOException("Could not finish the update: " + e.getMessage(), e);
        }
    }

    /** Cancels a half-done update: the tree is the saved version it started from, and nothing was committed. */
    public void abortMerge() throws IOException {
        try (Git git = open()) {
            git.reset().setMode(ResetType.HARD).setRef("HEAD").call();
            Repository repo = git.getRepository();
            repo.writeMergeHeads(null);
            repo.writeMergeCommitMsg(null);
        } catch (Exception e) {
            throw new IOException("Could not cancel the update: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Remotes: My copy and Original (39 §2, §7)
    // -------------------------------------------------------------------------

    /**
     * The one remote Studio wrote before the Versions tab: the private backup repo of the old <i>Push</i>. It
     * was always the user's own, so {@link #adoptLegacyBackup} makes it {@link Remote#MINE}.
     */
    private static final String LEGACY_BACKUP = "origin";

    /** {@code remote}'s URL, or null when the project has none. */
    public String remoteUrl(Remote remote) {
        return remoteUrl(remote.id());
    }

    /** The URL of the remote named {@code name}, or null. */
    public String remoteUrl(String name) {
        if (!isRepo() || name == null) return null;
        try (Git git = open()) {
            String url = git.getRepository().getConfig().getString("remote", name, "url");
            return url == null || url.isBlank() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Dev view: branches and remotes (39 §9)
    // -------------------------------------------------------------------------

    /** One remote as the Dev view lists it: {@code ahead}/{@code behind} by the last fetch, -1 when never read. */
    public record RemoteInfo(String name, String url, int ahead, int behind) {
    }

    /** The local branches, sorted; the current one among them. */
    public List<String> branches() throws IOException {
        if (!isRepo()) return List.of();
        try (Git git = open()) {
            List<String> out = new ArrayList<>();
            for (Ref ref : git.branchList().call()) out.add(Repository.shortenRefName(ref.getName()));
            return out.stream().sorted().toList();
        } catch (Exception e) {
            throw new IOException("Could not list the branches: " + e.getMessage(), e);
        }
    }

    /** A new branch at {@code HEAD}, not switched to. */
    public void createBranch(String name) throws IOException {
        ensureInitialized();
        try (Git git = open()) {
            if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + name)) {
                throw new IOException("“" + name + "” cannot be a branch name.");
            }
            git.branchCreate().setName(name).call();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not create " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Switches to {@code name}. The tree must be saved — the caller takes an {@code AUTO} version first — and
     * no update or merge may be half done.
     */
    public void switchTo(String name) throws IOException {
        if (merging()) throw new IOException("A merge is in progress — finish or cancel it first.");
        try (Git git = open()) {
            if (!git.status().call().isClean()) throw new IOException("Save your changes before switching.");
            git.checkout().setName(name).call();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not switch to " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deletes {@code name}, which must not be the current branch and must be merged: a branch whose versions
     * are nowhere else is refused, since deleting it would lose them ({@code 39} §10, nothing rewrites history).
     */
    public void deleteBranch(String name) throws IOException {
        try (Git git = open()) {
            if (name.equals(git.getRepository().getBranch())) {
                throw new IOException("Switch to another branch before deleting " + name + ".");
            }
            git.branchDelete().setBranchNames(name).setForce(false).call();
        } catch (org.eclipse.jgit.api.errors.NotMergedException e) {
            throw new IOException(name + " has versions no other branch has; merge it first.", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not delete " + name + ": " + e.getMessage(), e);
        }
    }

    /** Every remote, with how the current branch stands against its branch of the same name there. */
    public List<RemoteInfo> remotes() throws IOException {
        if (!isRepo()) return List.of();
        try (Git git = open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            String branch = repo.getBranch();
            ObjectId head = repo.resolve("HEAD");
            List<RemoteInfo> out = new ArrayList<>();
            for (String name : new java.util.TreeSet<>(repo.getRemoteNames())) {
                Ref there = repo.exactRef("refs/remotes/" + name + "/" + branch);
                int ahead = -1;
                int behind = -1;
                if (there != null && head != null) {
                    ahead = count(walk, head, there.getObjectId());
                    behind = count(walk, there.getObjectId(), head);
                }
                out.add(new RemoteInfo(name, remoteUrl(name), ahead, behind));
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not list the remotes: " + e.getMessage(), e);
        }
    }

    /** How many commits reachable from {@code from} are not reachable from {@code not}. */
    private static int count(RevWalk walk, ObjectId from, ObjectId not) throws IOException {
        walk.reset();
        walk.markStart(walk.parseCommit(from));
        walk.markUninteresting(walk.parseCommit(not));
        int n = 0;
        for (RevCommit ignored : walk) n++;
        return n;
    }

    /**
     * Adds a remote. HTTPS only, and never an address carrying a user or a token: what goes in
     * {@code .git/config} is readable by every tool on the machine ({@code 39} §10).
     */
    public void addRemote(String name, String url) throws IOException {
        String problem = remoteProblem(name, url);
        if (problem != null) throw new IOException(problem);
        if (remoteUrl(name) != null) throw new IOException("A remote named " + name + " exists already.");
        ensureInitialized();
        try (Git git = open()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", name, "url", url.trim());
            config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
            config.save();
        } catch (Exception e) {
            throw new IOException("Could not add the remote: " + e.getMessage(), e);
        }
    }

    /** Why {@code name}/{@code url} cannot be added as a remote, or null when they can. */
    static String remoteProblem(String name, String url) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) return "A remote name is letters, digits, . _ or -.";
        if (url == null) return "The address is empty.";
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "That is not an address.";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return "Only https:// addresses can be added.";
        }
        if (uri.getRawUserInfo() != null) return "An address must not carry a user name or a token.";
        return null;
    }

    /** Points {@code remote} at {@code url} — add or set-url in one call. The URL is plain HTTPS, no token. */
    public void setRemote(Remote remote, String url) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("Remote URL must not be empty.");
        ensureInitialized();
        try (Git git = open()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", remote.id(), "url", url.trim());
            config.setString("remote", remote.id(), "fetch", "+refs/heads/*:refs/remotes/" + remote.id() + "/*");
            config.save();
        } catch (Exception e) {
            throw new IOException("Could not set the remote: " + e.getMessage(), e);
        }
    }

    /**
     * Renames the old backup remote {@code origin} to {@link Remote#MINE}, with its remote-tracking refs, when
     * the project has the one and not the other. Returns whether it did. Idempotent.
     */
    public boolean adoptLegacyBackup() throws IOException {
        if (!isRepo()) return false;
        try (Git git = open()) {
            Repository repo = git.getRepository();
            StoredConfig config = repo.getConfig();
            String url = config.getString("remote", LEGACY_BACKUP, "url");
            if (url == null || url.isBlank() || remoteUrl(Remote.MINE) != null) return false;
            config.unsetSection("remote", LEGACY_BACKUP);
            config.setString("remote", Remote.MINE.id(), "url", url);
            config.setString("remote", Remote.MINE.id(), "fetch", "+refs/heads/*:refs/remotes/" + Remote.MINE.id() + "/*");
            config.save();
            String from = "refs/remotes/" + LEGACY_BACKUP + "/";
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(from)) {
                org.eclipse.jgit.lib.RefUpdate moved = repo.updateRef(
                        "refs/remotes/" + Remote.MINE.id() + "/" + ref.getName().substring(from.length()));
                moved.setNewObjectId(ref.getObjectId());
                moved.forceUpdate();
                org.eclipse.jgit.lib.RefUpdate gone = repo.updateRef(ref.getName());
                gone.setForceUpdate(true);
                gone.delete();
            }
            return true;
        } catch (Exception e) {
            throw new IOException("Could not adopt the old backup remote: " + e.getMessage(), e);
        }
    }

    /** The current branch's name ({@code main}, for a project Studio cloned). */
    public String branch() throws IOException {
        try (Git git = open()) {
            return git.getRepository().getBranch();
        }
    }

    /**
     * Pushes the current branch to {@code remoteBranch} on {@code remote}, with the tags and the version names
     * ({@link #NAMES}). Returns the local branch. Never forced: a remote that has versions this computer lacks
     * is reported, not overwritten.
     *
     * @param token a GitHub token, handed to JGit for this call only
     */
    public String push(Remote remote, String remoteBranch, String token) throws IOException {
        return push(remote, remoteBranch, token, false);
    }

    /**
     * {@link #push(Remote, String, String)}, forced when {@code force} — for a {@code suggest/…} branch only,
     * the one branch Studio moves ({@code 39} §10): a suggestion made again replaces the last one.
     */
    public String push(Remote remote, String remoteBranch, String token, boolean force) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("Not signed in to GitHub.");
        return push(remote.id(), remoteBranch, token, force);
    }

    /**
     * The push itself, to any remote by name — the Dev view's <i>Push</i> names one of its own. The token goes
     * only to GitHub ({@link #tokenFor}); another host is pushed to without one.
     */
    public String push(String remoteName, String remoteBranch, String token, boolean force) throws IOException {
        if (!isRepo()) throw new IOException("Nothing to push — this project has no history yet.");
        String url = remoteUrl(remoteName);
        if (url == null) throw new IOException("This project has no '" + remoteName + "' remote yet.");
        String sent = tokenFor(url, token);
        try (Git git = open()) {
            String branch = git.getRepository().getBranch();
            List<RefSpec> specs = new ArrayList<>();
            specs.add(new RefSpec((force ? "+" : "") + "refs/heads/" + branch + ":refs/heads/" + remoteBranch));
            boolean notes = git.getRepository().exactRef(NAMES) != null;
            if (notes) specs.add(new RefSpec(NAMES + ":" + NAMES));
            var push = git.push()
                    .setRemote(remoteName)
                    .setRefSpecs(specs)
                    .setPushTags()
                    .setForce(false);
            if (sent != null) push.setCredentialsProvider(credentials(sent));
            Iterable<PushResult> results = push.call();
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    // Names are a courtesy: a notes ref that moved on the other side must not fail the push.
                    if (!update.getRemoteName().equals(NAMES)) checkUpdate(update);
                }
            }
            return branch;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Push failed: " + e.getMessage(), e);
        }
    }

    /** Fetches {@code remote}'s branches and tags. {@code token} may be null for a public repository. */
    public void fetch(Remote remote, String token) throws IOException {
        fetch(remote.id(), token);
    }

    /** Fetches the remote named {@code remoteName}; the token goes only to GitHub ({@link #tokenFor}). */
    public void fetch(String remoteName, String token) throws IOException {
        String sent = tokenFor(remoteUrl(remoteName), token);
        try (Git git = open()) {
            var fetch = git.fetch().setRemote(remoteName).setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS);
            if (sent != null) fetch.setCredentialsProvider(credentials(sent));
            fetch.call();
        } catch (Exception e) {
            throw new IOException("Could not read " + remoteName + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code token} when {@code url} is a GitHub HTTPS address, else null: the user's GitHub token is theirs to
     * give GitHub, and a remote added by hand may be any host ({@code 39} §10 — a token goes nowhere else).
     */
    static String tokenFor(String url, String token) {
        if (url == null || token == null || token.isBlank()) return null;
        return url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://github.com/") ? token : null;
    }

    /**
     * How many versions of this computer's branch {@code remote}'s {@code remoteBranch} lacks, by the last
     * fetch or push — no network. -1 when nothing was ever pushed there.
     */
    public int notIn(Remote remote, String remoteBranch) throws IOException {
        try (Git git = open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            Ref there = repo.exactRef("refs/remotes/" + remote.id() + "/" + remoteBranch);
            if (head == null) return 0;
            if (there == null) return -1;
            walk.markStart(walk.parseCommit(head));
            walk.markUninteresting(walk.parseCommit(there.getObjectId()));
            int n = 0;
            for (RevCommit ignored : walk) n++;
            return n;
        }
    }

    /** The tag names {@code remote} has, asked over the network; {@code token} may be null. */
    public List<String> remoteTags(Remote remote, String token) throws IOException {
        String url = remoteUrl(remote);
        if (url == null) return List.of();
        try {
            var ls = Git.lsRemoteRepository().setRemote(url).setTags(true).setHeads(false).setTimeout(15);
            if (token != null && !token.isBlank()) ls.setCredentialsProvider(credentials(token));
            List<String> out = new ArrayList<>();
            for (Ref ref : ls.call()) {
                String name = Repository.shortenRefName(ref.getName());
                if (!name.endsWith("^{}")) out.add(name);
            }
            return out;
        } catch (Exception e) {
            throw new IOException("Could not list the original's releases: " + e.getMessage(), e);
        }
    }

    /**
     * Clones {@code url} into {@code dest} at {@code tag}: the remote is {@link Remote#ORIGINAL}, and a branch
     * {@code main} starts at the tag and is checked out — an installed bot is a real clone of its author's
     * repository ({@code 39} §7).
     */
    public static void cloneAt(String url, String tag, Path dest, String token) throws IOException {
        var clone = Git.cloneRepository().setURI(url).setDirectory(dest.toFile())
                .setRemote(Remote.ORIGINAL.id()).setNoCheckout(true);
        if (token != null && !token.isBlank()) clone.setCredentialsProvider(credentials(token));
        try (Git git = clone.call()) {
            ObjectId at = git.getRepository().resolve("refs/tags/" + tag + "^{commit}");
            if (at == null) throw new IOException("The release " + tag + " is not in " + url + ".");
            // The clone made a local branch at the author's default tip (named whatever theirs is): the user's
            // line is main, at the tag, whatever the author did after it.
            Repository repo = git.getRepository();
            org.eclipse.jgit.lib.RefUpdate main = repo.updateRef("refs/heads/main");
            main.setNewObjectId(at);
            main.setForceUpdate(true);
            main.update();
            repo.updateRef("HEAD").link("refs/heads/main");
            git.reset().setMode(ResetType.HARD).setRef(at.name()).call();
            if (!"main".equals(repo.getBranch())) {
                throw new IOException("Could not start a branch at " + tag + ".");
            }
            // Build output must never become a version, whatever the author's repository ignores.
            new ProjectVcs(dest).writeGitignore();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not install from " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Joins a project installed from a zip to its author's history, once ({@code 39} §7): {@link Remote#ORIGINAL}
     * is set to {@code url}, the tag is fetched, and a merge commit whose parents are {@code HEAD} and the tag's
     * commit is written <b>with the user's tree unchanged</b> — the tag becomes the merge base an update needs,
     * and no edit moves. Returns false when {@code HEAD} already contains the tag (a clone, or attached before).
     */
    public boolean attach(String url, String tag, String label, String token) throws IOException {
        ensureInitialized();
        if (remoteUrl(Remote.ORIGINAL) == null) setRemote(Remote.ORIGINAL, url);
        try (Git git = open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            var fetch = git.fetch().setRemote(Remote.ORIGINAL.id())
                    .setRefSpecs(new RefSpec("+refs/tags/" + tag + ":refs/tags/" + tag))
                    .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS);
            if (token != null && !token.isBlank()) fetch.setCredentialsProvider(credentials(token));
            fetch.call();
            ObjectId tagged = repo.resolve("refs/tags/" + tag + "^{commit}");
            if (tagged == null) throw new IOException("The release " + tag + " is not in " + url + ".");
            RevCommit head = walk.parseCommit(repo.resolve("HEAD"));
            RevCommit base = walk.parseCommit(tagged);
            if (walk.isMergedInto(base, head)) return false;
            keepMine(repo, head, base, VersionOrigin.INSTALL, label);
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not link to the original: " + e.getMessage(), e);
        }
    }

    /**
     * Before the first git publish into a repository that already has a history this project does not share
     * — one Studio published through the Git Data API before 2026-09-25, whose commits were built on GitHub
     * from a snapshot — fetches {@code remoteBranch} of {@code remote} and records a merge of it with this
     * computer's tree unchanged, so the push that follows is a fast-forward and nothing on GitHub is
     * overwritten. Returns false, changing nothing, when the branch does not exist there yet or already shares
     * history with {@code HEAD}: a remote that is merely ahead is refused by the push, as it should be.
     */
    public boolean joinUnrelated(Remote remote, String remoteBranch, String label, String token) throws IOException {
        if (!isRepo() || remoteUrl(remote) == null) return false;
        try (Git git = open(); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            String tracking = "refs/remotes/" + remote.id() + "/" + remoteBranch;
            var fetch = git.fetch().setRemote(remote.id())
                    .setRefSpecs(new RefSpec("+refs/heads/" + remoteBranch + ":" + tracking))
                    .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS);
            if (token != null && !token.isBlank()) fetch.setCredentialsProvider(credentials(token));
            try {
                fetch.call();
            } catch (org.eclipse.jgit.api.errors.TransportException missing) {
                // An empty repository has no such branch; there is nothing to join.
                return false;
            }
            Ref there = repo.exactRef(tracking);
            ObjectId headId = repo.resolve("HEAD");
            if (there == null || headId == null) return false;
            RevCommit head = walk.parseCommit(headId);
            RevCommit theirs = walk.parseCommit(there.getObjectId());
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(head);
            walk.markStart(theirs);
            if (walk.next() != null) return false;
            keepMine(repo, head, theirs, VersionOrigin.PUBLISH, label);
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read your copy on GitHub: " + e.getMessage(), e);
        }
    }

    /**
     * An annotated tag {@code name} at {@code HEAD}, unless the tag exists already — a publish resumed after
     * its tag was made keeps the commit it named then. Returns the commit the tag names.
     */
    public String tagHead(String name, String message) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Tag name must not be empty.");
        ensureInitialized();
        try (Git git = open()) {
            ObjectId had = git.getRepository().resolve("refs/tags/" + name.trim() + "^{commit}");
            if (had != null) return had.name();
            git.tag().setName(name.trim()).setMessage(message).setTagger(author).call();
            return git.getRepository().resolve("refs/tags/" + name.trim() + "^{commit}").name();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Tag failed: " + e.getMessage(), e);
        }
    }

    /**
     * Moves {@code HEAD} to a new merge commit of {@code head} and {@code other} whose tree is {@code head}'s:
     * the histories are joined, and no file moves.
     */
    private void keepMine(Repository repo, RevCommit head, RevCommit other, VersionOrigin origin, String label)
            throws IOException {
        org.eclipse.jgit.lib.CommitBuilder merge = new org.eclipse.jgit.lib.CommitBuilder();
        merge.setTreeId(head.getTree());
        merge.setParentIds(head, other);
        merge.setAuthor(author);
        merge.setCommitter(author);
        merge.setMessage(origin.stamp(label));
        ObjectId id;
        try (org.eclipse.jgit.lib.ObjectInserter inserter = repo.newObjectInserter()) {
            id = inserter.insert(merge);
            inserter.flush();
        }
        org.eclipse.jgit.lib.RefUpdate update = repo.updateRef("HEAD");
        update.setNewObjectId(id);
        update.setExpectedOldObjectId(head);
        update.setRefLogMessage(label, false);
        switch (update.update()) {
            case FAST_FORWARD, NEW, FORCED -> { }
            default -> throw new IOException("Could not record " + label + ".");
        }
    }

    /** How GitHub takes an OAuth token over HTTPS — per call, never stored. */
    private static UsernamePasswordCredentialsProvider credentials(String token) {
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    /** Turns one ref's push outcome into a readable failure; a successful/no-op update passes silently. */
    private static void checkUpdate(RemoteRefUpdate update) throws IOException {
        switch (update.getStatus()) {
            case OK, UP_TO_DATE -> { /* pushed, or already there */ }
            case REJECTED_NONFASTFORWARD -> throw new IOException(
                    "Your copy on GitHub has versions this computer doesn't, so nothing was overwritten.");
            case REJECTED_OTHER_REASON -> throw new IOException("Push rejected: "
                    + (update.getMessage() == null ? "the remote refused the update." : update.getMessage()));
            default -> throw new IOException("Push failed (" + update.getStatus() + ")"
                    + (update.getMessage() == null ? "." : ": " + update.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void createTag(String name, String message) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Tag name must not be empty.");
        ensureInitialized();
        try (Git git = open()) {
            git.tag().setName(name.trim()).setMessage(message).setTagger(author).call();
        } catch (Exception e) {
            throw new IOException("Tag failed: " + e.getMessage(), e);
        }
    }

    /** Maps each tagged commit SHA (peeled to the commit) to the tag short-names pointing at it. */
    private Map<String, List<String>> tagsByCommit(Git git) throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        Repository repo = git.getRepository();
        for (Ref tag : git.tagList().call()) {
            Ref peeled = repo.getRefDatabase().peel(tag);
            ObjectId id = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : tag.getObjectId();
            String shortName = Repository.shortenRefName(tag.getName());
            map.computeIfAbsent(id.name(), k -> new ArrayList<>()).add(shortName);
        }
        return map;
    }

    /** The repository, for this package's readers ({@link VersionReader}); the caller closes it. */
    Git open() throws IOException {
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(projectDir.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build();
        return new Git(repo);
    }

    private void writeGitignore() throws IOException {
        Path gitignore = projectDir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, String.join(System.lineSeparator(), GITIGNORE_LINES)
                + System.lineSeparator());
    }
}
