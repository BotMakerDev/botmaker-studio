package com.botmaker.studio.project.vcs;

import com.botmaker.cli.publish.Publish;
import com.botmaker.cli.repair.Compile;
import com.botmaker.cli.repair.Damage;
import com.botmaker.cli.repair.ProjectSide;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * What a damaged project is put back from: the last-compiling record, saves, and the newest compiling commit when
 * there is no record (the data-safety spec, §2). The record and the saves are commits written from the working tree
 * through an index of their own, so neither the user's index nor the current branch moves, and a push, which sends the
 * current branch only, never carries them.
 *
 * <p>A {@code .git} file ({@link ProjectVcs#sharedRepository()}) gets neither record nor saves: somebody else's tooling
 * also writes that history. Blocking; call off the FX thread.
 */
public final class RepairPoints {

    /** The last state of the project that compiled: a commit outside every branch, so no line in the timeline. */
    public static final String RECORD = "refs/botmaker/last-compiling";

    /** A save's branch, then its date. */
    public static final String SAVED_BRANCH = Publish.SAVED_BRANCH;

    /** The trailer of a save: the record when it was made, as the record moves on afterwards. */
    public static final String LAST_COMPILING_TRAILER = "BotMaker-Last-Compiling";

    private static final DateTimeFormatter SAVE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    /** Inside {@code .git}, so it goes with the record it holds. */
    private static final String HOLD_FILE = "botmaker-record-held";

    /** Inside {@code .git}: the HEAD last searched, and the commit the fallback found from it. */
    private static final String FALLBACK_FILE = "botmaker-newest-compiling";

    /** How many different trees the fallback tries before it gives up: each one is a compile. */
    private static final int MOST_TRIED = 100;

    private final Path projectDir;
    private final ProjectVcs vcs;

    public RepairPoints(Path projectDir) {
        this.projectDir = projectDir;
        this.vcs = new ProjectVcs(projectDir);
    }

    public static boolean isSavedBranch(String branch) {
        return branch != null && branch.startsWith(SAVED_BRANCH);
    }

    /**
     * Moves the record to the project as it is on disk, after a compile that succeeded. Answers whether it moved: it
     * stays while {@link #held()}, mid-merge, in a shared repository, with no repository, and when nothing changed.
     */
    public boolean recordCompiled() throws IOException {
        if (!vcs.isRepo() || vcs.sharedRepository() || vcs.merging() || held()) return false;
        try (Git git = vcs.open()) {
            Repository repo = git.getRepository();
            try (ObjectInserter inserter = repo.newObjectInserter(); RevWalk walk = new RevWalk(repo)) {
                ObjectId tree = workingTree(repo, inserter);
                Ref record = repo.exactRef(RECORD);
                if (record != null && walk.parseCommit(record.getObjectId()).getTree().getId().equals(tree)) {
                    return false;
                }
                ObjectId commit = commit(repo, inserter, tree, VersionOrigin.AUTO.stamp("Last compiling"), null);
                inserter.flush();
                RefUpdate update = repo.updateRef(RECORD);
                update.setNewObjectId(commit);
                update.setForceUpdate(true);
                update.disableRefLog();
                switch (update.update()) {
                    case NEW, FORCED, FAST_FORWARD -> { }
                    default -> throw new IOException("Could not move the last-compiling record.");
                }
                return true;
            }
        }
    }

    /** The commit of the record, if there is one. */
    public Optional<String> lastCompiling() throws IOException {
        if (!vcs.isRepo()) return Optional.empty();
        try (Git git = vcs.open()) {
            Ref record = git.getRepository().exactRef(RECORD);
            return record == null ? Optional.empty() : Optional.of(record.getObjectId().name());
        }
    }

    /** Holds the record still: an outside change removed members, and the loss must not become the good state. */
    public void hold() throws IOException {
        Path gitDir = vcs.gitDir();
        if (gitDir != null) Files.writeString(gitDir.resolve(HOLD_FILE), "");
    }

    /** The user answered the outside change (<i>Leave as is</i>): the record may move again. */
    public void release() throws IOException {
        Path gitDir = vcs.gitDir();
        if (gitDir != null) Files.deleteIfExists(gitDir.resolve(HOLD_FILE));
    }

    public boolean held() {
        Path gitDir = vcs.gitDir();
        return gitDir != null && Files.exists(gitDir.resolve(HOLD_FILE));
    }

    /**
     * Saves the project as it is on disk, untracked files included, on a new {@code botmaker/saved/…} branch, staying
     * on the current one. Answers the branch; nothing in a shared repository. A folder with no repository gets one.
     *
     * @param label the save's first line, saying why it was made
     */
    public Optional<String> save(String label) throws IOException {
        vcs.ensureInitialized();
        if (vcs.sharedRepository()) return Optional.empty();
        try (Git git = vcs.open()) {
            Repository repo = git.getRepository();
            Optional<String> record = lastCompiling();
            try (ObjectInserter inserter = repo.newObjectInserter()) {
                // The record as a second parent, as a trailer alone keeps nothing: the next compile would orphan it.
                ObjectId commit = commit(repo, inserter, workingTree(repo, inserter), saveMessage(label, record),
                        record.map(ObjectId::fromString).orElse(null));
                inserter.flush();
                return Optional.of(createBranch(repo, commit));
            }
        }
    }

    /** The commit that was last compiling when a save was made, from its message. */
    public static Optional<String> lastCompilingOf(String fullMessage) {
        String prefix = LAST_COMPILING_TRAILER + ":";
        return fullMessage.lines().filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()).strip()).reduce((first, last) -> last);
    }

    /**
     * The newest commit of the current branch that compiles, for a project with no record. {@code compiles} is given
     * a folder holding that commit's pom, Java sources and jars. Commits with the same of those are tried once, and
     * it gives up after {@value #MOST_TRIED} different sets.
     */
    public Optional<String> newestCompiling(Predicate<Path> compiles) throws IOException {
        if (!vcs.isRepo()) return Optional.empty();
        try (Git git = vcs.open()) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) return Optional.empty();
            try (RevWalk walk = new RevWalk(repo)) {
                walk.sort(RevSort.TOPO);
                walk.markStart(walk.parseCommit(head));
                Set<Map<String, ObjectId>> seen = new HashSet<>();
                for (RevCommit c : walk) {
                    Map<String, ObjectId> inputs = compileInputs(repo, c);
                    if (!seen.add(inputs)) continue;
                    if (seen.size() > MOST_TRIED) return Optional.empty();
                    if (compilesAt(repo, inputs, compiles)) return Optional.of(c.name());
                }
                return Optional.empty();
            }
        }
    }

    /**
     * The project on disk against the record, or else the newest commit that compiles. That search may be up to
     * {@value #MOST_TRIED} Maven builds; what it finds is remembered for that HEAD. Finding nothing is not, as it may
     * only mean Maven had no network.
     *
     * @param compile what the last compile of the project on disk said
     * @throws IOException also when Maven could not be run: that says nothing about the project
     */
    public Damage.Report damage(Compile compile) throws IOException {
        ProjectSide disk = ProjectSide.folder(projectDir);
        if (!vcs.isRepo()) return Damage.noRepository(disk, compile);
        Optional<String> point = lastCompiling();
        if (point.isEmpty()) point = fallback();
        return point.isEmpty() ? Damage.noRepairPoint(disk, compile) : Damage.compare(side(point.get()), disk, compile);
    }

    private Optional<String> fallback() throws IOException {
        Path gitDir = vcs.gitDir();
        Path memo = gitDir == null ? null : gitDir.resolve(FALLBACK_FILE);
        String head;
        try (Git git = vcs.open()) {
            ObjectId id = git.getRepository().resolve(Constants.HEAD);
            if (id == null) return Optional.empty();
            head = id.name();
        }
        if (memo != null && Files.exists(memo)) {
            List<String> remembered = Files.readAllLines(memo);
            if (remembered.size() == 2 && remembered.getFirst().equals(head)) return Optional.of(remembered.getLast());
        }
        Optional<String> found;
        try {
            found = newestCompiling(Compile::compiles);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        if (memo != null && found.isPresent()) Files.writeString(memo, head + "\n" + found.get() + "\n");
        return found;
    }

    /** A commit, as one side of a comparison. */
    public ProjectSide side(String commit) throws IOException {
        Map<String, ObjectId> blobs = new TreeMap<>();
        try (Git git = vcs.open(); RevWalk commits = new RevWalk(git.getRepository());
             TreeWalk walk = new TreeWalk(git.getRepository())) {
            walk.addTree(commits.parseCommit(ObjectId.fromString(commit)).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                if (walk.getFileMode(0) != FileMode.GITLINK) blobs.put(walk.getPathString(), walk.getObjectId(0));
            }
        }
        return new ProjectSide() {
            @Override
            public Set<String> paths() {
                return blobs.keySet();
            }

            @Override
            public long size(String path) throws IOException {
                try (Git git = vcs.open(); ObjectReader reader = git.getRepository().newObjectReader()) {
                    return reader.getObjectSize(blobs.get(path), Constants.OBJ_BLOB);
                }
            }

            @Override
            public byte[] read(String path) throws IOException {
                try (Git git = vcs.open(); ObjectReader reader = git.getRepository().newObjectReader()) {
                    return reader.open(blobs.get(path), Constants.OBJ_BLOB).getBytes();
                }
            }
        };
    }

    private static boolean compilesAt(Repository repo, Map<String, ObjectId> inputs, Predicate<Path> compiles)
            throws IOException {
        Path folder = Files.createTempDirectory("botmaker-repair");
        try {
            export(repo, inputs, folder);
            return compiles.test(folder);
        } finally {
            try (var files = Files.walk(folder)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    /** Only what a compile reads, by path: a bot's pictures can be tens of MB a commit. */
    private static Map<String, ObjectId> compileInputs(Repository repo, RevCommit commit) throws IOException {
        Map<String, ObjectId> inputs = new HashMap<>();
        try (TreeWalk walk = new TreeWalk(repo)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                String path = walk.getPathString();
                if (path.endsWith(".java") || path.endsWith(".jar") || path.equals("pom.xml")) {
                    inputs.put(path, walk.getObjectId(0));
                }
            }
        }
        return inputs;
    }

    private static void export(Repository repo, Map<String, ObjectId> inputs, Path folder) throws IOException {
        try (ObjectReader reader = repo.newObjectReader()) {
            for (Map.Entry<String, ObjectId> input : inputs.entrySet()) {
                Path to = folder.resolve(input.getKey()).normalize();
                if (!to.startsWith(folder)) continue;
                Files.createDirectories(to.getParent());
                ObjectLoader blob = reader.open(input.getValue());
                try (InputStream in = blob.openStream()) {
                    Files.copy(in, to);
                }
            }
        }
    }

    private static String saveMessage(String label, Optional<String> lastCompiling) {
        String stamped = VersionOrigin.SAVED_STATE.stamp(label);
        return lastCompiling.map(sha -> stamped + LAST_COMPILING_TRAILER + ": " + sha + "\n").orElse(stamped);
    }

    /** A new branch, whatever else this minute saved: a save never replaces a save. */
    private static String createBranch(Repository repo, ObjectId commit) throws IOException {
        String base = SAVED_BRANCH + LocalDateTime.now().format(SAVE_NAME);
        String name = base;
        for (int n = 2; repo.exactRef(Constants.R_HEADS + name) != null; n++) name = base + "-" + n;
        RefUpdate update = repo.updateRef(Constants.R_HEADS + name);
        update.setNewObjectId(commit);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        if (update.update() != RefUpdate.Result.NEW) {
            // A branch named botmaker or botmaker/saved leaves no room for one under it.
            throw new IOException("Could not write the save " + name + ": does a branch named botmaker block it?");
        }
        return name;
    }

    private ObjectId commit(Repository repo, ObjectInserter inserter, ObjectId tree, String message, ObjectId kept)
            throws IOException {
        PersonIdent who = vcs.now();
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        Set<ObjectId> parents = new LinkedHashSet<>();
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head != null) parents.add(head);
        if (kept != null) parents.add(kept);
        commit.setParentIds(List.copyOf(parents));
        commit.setAuthor(who);
        commit.setCommitter(who);
        commit.setMessage(message);
        return inserter.insert(commit);
    }

    /**
     * The tree of what is on disk: a file git follows or would follow, as {@code add} would see it. A file the index
     * says is unchanged keeps its object, so a bot's pictures are not hashed again on each compile. The index itself
     * is only read.
     */
    private static ObjectId workingTree(Repository repo, ObjectInserter inserter) throws IOException {
        DirCache tree = DirCache.newInCore();
        DirCacheBuilder builder = tree.builder();
        try (TreeWalk walk = new TreeWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            int inIndex = walk.addTree(new DirCacheIterator(repo.readDirCache()));
            int onDisk = walk.addTree(new FileTreeIterator(repo));
            walk.setRecursive(false);
            String last = null;
            while (walk.next()) {
                DirCacheIterator index = walk.getTree(inIndex, DirCacheIterator.class);
                FileTreeIterator file = walk.getTree(onDisk, FileTreeIterator.class);
                if (file == null) continue; // Gone from the disk: the state saved is the state on the disk.
                if (index == null && file.isEntryIgnored()) continue;
                if (walk.isSubtree()) {
                    walk.enterSubtree();
                    continue;
                }
                String path = walk.getPathString();
                if (path.equals(last)) continue; // A conflicted file's other stages, mid-merge.
                last = path;
                FileMode mode = file.getEntryFileMode();
                if (mode == FileMode.GITLINK) {
                    if (index != null) builder.add(entry(walk, index.getEntryFileMode(), index.getEntryObjectId()));
                    continue;
                }
                DirCacheEntry known = index == null ? null : index.getDirCacheEntry();
                boolean same = known != null && known.getStage() == DirCacheEntry.STAGE_0
                        && !file.isModified(known, true, reader);
                builder.add(entry(walk, mode, same ? known.getObjectId() : blob(repo, path, file, inserter)));
            }
        }
        builder.finish();
        return tree.writeTree(inserter);
    }

    /** The file's own bytes, never through git's line-ending or clean filters: a save holds what was there. */
    private static ObjectId blob(Repository repo, String path, FileTreeIterator file, ObjectInserter inserter)
            throws IOException {
        if (file.getEntryFileMode() == FileMode.SYMLINK) {
            try (InputStream in = file.openEntryStream()) {
                return inserter.insert(Constants.OBJ_BLOB, file.getEntryContentLength(), in);
            }
        }
        Path onDisk = repo.getWorkTree().toPath().resolve(path);
        try (InputStream in = Files.newInputStream(onDisk)) {
            return inserter.insert(Constants.OBJ_BLOB, Files.size(onDisk), in);
        }
    }

    private static DirCacheEntry entry(TreeWalk walk, FileMode mode, ObjectId id) {
        DirCacheEntry entry = new DirCacheEntry(walk.getRawPath());
        entry.setFileMode(mode);
        entry.setObjectId(id);
        return entry;
    }
}
