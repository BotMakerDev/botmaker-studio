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
                default -> throw new IOException("The update could not start (" + result.getMergeStatus() + ").");
            };
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not update: " + e.getMessage(), e);
        }
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
        if (!unresolved().isEmpty()) throw new IOException("Some files are still to decide.");
        try (Git git = open()) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit c = git.commit().setAuthor(author).setCommitter(author)
                    .setMessage(VersionOrigin.UPDATE.stamp(label)).call();
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
        if (!isRepo()) return null;
        try (Git git = open()) {
            String url = git.getRepository().getConfig().getString("remote", remote.id(), "url");
            return url == null || url.isBlank() ? null : url;
        } catch (Exception e) {
            return null;
        }
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
        if (!isRepo()) throw new IOException("Nothing to push — this project has no history yet.");
        if (remoteUrl(remote) == null) throw new IOException("This project has no '" + remote.id() + "' remote yet.");
        try (Git git = open()) {
            String branch = git.getRepository().getBranch();
            List<RefSpec> specs = new ArrayList<>();
            specs.add(new RefSpec((force ? "+" : "") + "refs/heads/" + branch + ":refs/heads/" + remoteBranch));
            boolean notes = git.getRepository().exactRef(NAMES) != null;
            if (notes) specs.add(new RefSpec(NAMES + ":" + NAMES));
            Iterable<PushResult> results = git.push()
                    .setRemote(remote.id())
                    .setRefSpecs(specs)
                    .setPushTags()
                    .setForce(false)
                    .setCredentialsProvider(credentials(token))
                    .call();
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
        try (Git git = open()) {
            var fetch = git.fetch().setRemote(remote.id()).setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS);
            if (token != null && !token.isBlank()) fetch.setCredentialsProvider(credentials(token));
            fetch.call();
        } catch (Exception e) {
            throw new IOException("Could not read " + remote.displayName().toLowerCase() + ": " + e.getMessage(), e);
        }
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

            org.eclipse.jgit.lib.CommitBuilder merge = new org.eclipse.jgit.lib.CommitBuilder();
            merge.setTreeId(head.getTree());
            merge.setParentIds(head, base);
            merge.setAuthor(author);
            merge.setCommitter(author);
            merge.setMessage(VersionOrigin.INSTALL.stamp(label));
            ObjectId id;
            try (org.eclipse.jgit.lib.ObjectInserter inserter = repo.newObjectInserter()) {
                id = inserter.insert(merge);
                inserter.flush();
            }
            org.eclipse.jgit.lib.RefUpdate update = repo.updateRef("HEAD");
            update.setNewObjectId(id);
            update.setExpectedOldObjectId(head);
            update.setRefLogMessage("attach to " + tag, false);
            switch (update.update()) {
                case FAST_FORWARD, NEW, FORCED -> { }
                default -> throw new IOException("Could not record the link to " + tag + ".");
            }
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not link to the original: " + e.getMessage(), e);
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
