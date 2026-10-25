package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.PomInfo;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.ProgramEntries;
import com.botmaker.studio.sharing.UnpackedBots;

import com.botmaker.studio.runtime.TreeCompile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The home's <i>My bots</i> (2026-10-25, spec § 1a): every project on this computer, the root's and the remembered
 * ones elsewhere, as cards. Pure over what {@link #read} gathers, so the rules are tested without a repository.
 *
 * <p>A remembered folder that is gone is a card that says so, not a silent gap: the user put it in the list.
 */
public final class MyBots {

    private MyBots() {}

    /** Whether a card's folder is there to open. */
    public enum State { READY, GONE }

    /** Whether a bot compiles as it is on disk (2026-10-25): unknown until checked, or when it could not be. */
    public enum CompileState { UNKNOWN, OK, ERRORS }

    /** One check: a fingerprint of the sources it was made on, and what it found. */
    private record Checked(long fingerprint, CompileState state) {}

    /** This session's checks, by folder: a card shown again is not compiled again until a source changes. */
    private static final Map<Path, Checked> CHECKED = new ConcurrentHashMap<>();

    /** One compile at a time, in the background: each is a resolve and a javac. */
    private static final ExecutorService COMPILER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mybots-compile");
        thread.setDaemon(true);
        return thread;
    });

    /** The checks under way, by folder: a home shown twice while one compiles waits on that one. */
    private static final Map<Path, CompletableFuture<CompileState>> RUNNING = new ConcurrentHashMap<>();

    /**
     * Whether the bot in {@code dir} compiles: this session's answer while its sources are unchanged, else
     * {@code check}'s, run on the background compiler. Reads no file on the caller's thread. Never fails: a check
     * that throws is {@code UNKNOWN}, and is asked again next time.
     */
    public static CompletableFuture<CompileState> compileState(Path dir, Function<Path, CompileState> check) {
        Path key = dir.toAbsolutePath().normalize();
        CompletableFuture<CompileState> answer = RUNNING.computeIfAbsent(key, k -> CompletableFuture
                .supplyAsync(() -> fingerprint(k))
                .thenCompose(fingerprint -> {
                    Checked known = CHECKED.get(k);
                    if (known != null && known.fingerprint() == fingerprint) {
                        return CompletableFuture.completedFuture(known.state());
                    }
                    return CompletableFuture.supplyAsync(() -> {
                        CompileState state;
                        try {
                            state = check.apply(k);
                        } catch (RuntimeException e) {
                            state = CompileState.UNKNOWN;
                        }
                        if (state != CompileState.UNKNOWN) CHECKED.put(k, new Checked(fingerprint, state));
                        return state;
                    }, COMPILER);
                }));
        // Outside computeIfAbsent: a check already done would otherwise remove itself from inside the map's update.
        answer.whenComplete((state, failed) -> RUNNING.remove(key, answer));
        return answer;
    }

    /**
     * The real check: {@link TreeCompile} over the folder, as an update's is. A dependency that did not resolve
     * (offline, a repository down) says nothing about the bot's code, so it is {@code UNKNOWN}, never broken.
     */
    public static CompileState compile(Path dir) {
        try {
            List<String> errors = TreeCompile.errors(ProjectConfig.forDirectory(dir));
            if (errors.stream().anyMatch(e -> e.startsWith(TreeCompile.UNRESOLVED))) return CompileState.UNKNOWN;
            return errors.isEmpty() ? CompileState.OK : CompileState.ERRORS;
        } catch (IOException e) {
            return CompileState.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompileState.UNKNOWN;
        }
    }

    /**
     * The newest change time of the pom and of any source under {@code src/main/java}, with how many files there
     * are: what a compile reads. Pictures under {@code resources} are left out, so a bot's screens cost no walk.
     */
    static long fingerprint(Path dir) {
        long newest = 0;
        long count = 0;
        Path sources = dir.resolve("src/main/java");
        try (Stream<Path> files = Stream.concat(Stream.of(dir.resolve("pom.xml")),
                Files.isDirectory(sources) ? Files.walk(sources) : Stream.empty())) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file)) continue;
                newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                count++;
            }
        } catch (IOException | UncheckedIOException e) {
            return -1;
        }
        return newest * 31 + count;
    }

    /**
     * What one project's folder says, read off the disk ({@link #read}).
     *
     * @param originalUrl the {@code original} remote's URL: the author's repository a bot was got from
     * @param version     the nearest tag, empty for a bot never released
     * @param runnable    whether a class holds {@code main}; a project that is no bot is only edited
     * @param lastUsed    when Studio last opened it, else when its folder last changed
     * @param title       the pom's {@code <name>}, set in Project Settings; the card shows the folder's without one
     * @param unpacked    a bot opened from a {@code .bmk} ({@link UnpackedBots}): its author and tag come from its
     *                    {@code bot.json}, and it has no history to merge an update into
     */
    public record Facts(Optional<String> originalUrl, Optional<String> version, boolean runnable,
                        LocalDateTime lastUsed, Optional<String> title, boolean unpacked) {

        /** A project's facts: one with a folder of its own, not an unpacked bot. */
        public Facts(Optional<String> originalUrl, Optional<String> version, boolean runnable, LocalDateTime lastUsed,
                     Optional<String> title) {
            this(originalUrl, version, runnable, lastUsed, title, false);
        }
    }

    /**
     * One card. {@code owner} is the author of a bot got from someone, empty for the user's own ("mine");
     * {@code available} is the catalog's newer tag; {@code unpacked} a bot opened from a {@code .bmk};
     * {@code original} the author's repository it came from, empty for the user's own.
     */
    public record MyBot(Path dir, String name, Optional<String> owner, Optional<String> version,
                        LocalDateTime lastUsed, Optional<String> available, boolean runnable, State state,
                        boolean unpacked, Optional<SyncModel.Slug> original) {

        /** A project's card. */
        public MyBot(Path dir, String name, Optional<String> owner, Optional<String> version, LocalDateTime lastUsed,
                     Optional<String> available, boolean runnable, State state) {
            this(dir, name, owner, version, lastUsed, available, runnable, state, false, Optional.empty());
        }
    }

    /**
     * The programs-menu entries of {@code bots}: each one that can run, by its author's repository, else by its
     * folder (spec § 5).
     */
    public static List<ProgramEntries.Entry> programEntries(List<MyBot> bots) {
        return bots.stream().filter(b -> b.state() == State.READY && b.runnable())
                .map(b -> b.original()
                        .map(s -> ProgramEntries.Entry.ofRepo(s.owner(), s.repo(), b.name(), b.dir()))
                        .orElseGet(() -> ProgramEntries.Entry.ofFolder(b.dir(), b.name())))
                .toList();
    }

    /**
     * The cards for {@code projectDirs}, most recently used first, gone ones last. {@code facts} is asked only of
     * a folder that is there; {@code latestTagByRepo} is the catalog's tag by lower-case {@code owner/repo}. An
     * unpacked bot whose author's repository is also a project here is left out: <i>Edit in Studio</i> made that
     * clone, and the card points to it (spec § 1a).
     */
    public static List<MyBot> scan(List<Path> projectDirs, Function<Path, Facts> facts,
                                   Map<String, String> latestTagByRepo) {
        List<MyBot> bots = new ArrayList<>();
        Set<String> cloned = new HashSet<>();
        Map<MyBot, String> unpackedRepo = new HashMap<>();
        for (Path dir : projectDirs) {
            String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
            if (Files.exists(dir) && !isProject(dir)) continue;   // there but no project (now): left out, as before
            if (!isProject(dir)) {
                bots.add(new MyBot(dir, name, Optional.empty(), Optional.empty(), LocalDateTime.MIN,
                        Optional.empty(), false, State.GONE));
                continue;
            }
            Facts f = facts.apply(dir);
            Optional<SyncModel.Slug> original = f.originalUrl().flatMap(SyncModel::slug);
            // An unpacked bot has no history to merge a newer tag into; a newer .bmk is opened beside it instead.
            Optional<String> available = f.unpacked() ? Optional.empty() : original
                    .map(s -> latestTagByRepo.get(key(s.owner(), s.repo())))
                    .flatMap(latest -> GalleryEntry.updateTarget(f.version().orElse(""), latest));
            MyBot bot = new MyBot(dir, f.title().orElse(name), original.map(SyncModel.Slug::owner), f.version(),
                    f.lastUsed(), available, f.runnable(), State.READY, f.unpacked(), original);
            bots.add(bot);
            original.map(s -> key(s.owner(), s.repo())).ifPresent(k -> {
                if (f.unpacked()) unpackedRepo.put(bot, k);
                else cloned.add(k);
            });
        }
        bots.removeIf(b -> cloned.contains(unpackedRepo.get(b)));
        bots.sort(Comparator.comparing((MyBot b) -> b.state() == State.GONE)
                .thenComparing(MyBot::lastUsed, Comparator.reverseOrder())
                .thenComparing(MyBot::name, String.CASE_INSENSITIVE_ORDER));
        return bots;
    }

    /** The catalog's key for a repository: {@code owner/repo}, lower case. */
    public static String key(String owner, String repo) {
        return (owner + "/" + repo).toLowerCase(Locale.ROOT);
    }

    /** The catalog's newest tag of each bot, by {@link #key}. */
    public static Map<String, String> latestTags(List<GalleryEntry> bots) {
        Map<String, String> tags = new HashMap<>();
        for (GalleryEntry e : bots) if (!e.tag().isBlank()) tags.put(key(e.owner(), e.repo()), e.tag());
        return tags;
    }

    /**
     * The folders My bots lists: the root's projects, then the remembered ones outside it — gone ones too, so
     * their card can say so — then the bots opened from a {@code .bmk}, each one's newest tag. Each folder once.
     */
    public static List<Path> folders(ProjectManager projects, List<Path> remembered, List<Path> unpacked) {
        Set<Path> dirs = new LinkedHashSet<>();
        projects.listProjects().forEach(p -> dirs.add(p.projectPath().toAbsolutePath().normalize()));
        for (Path dir : remembered) {
            Path d = dir.toAbsolutePath().normalize();
            if (!projects.isUnderRoot(d) && !UnpackedBots.isUnpacked(d)) dirs.add(d);
        }
        unpacked.forEach(d -> dirs.add(d.toAbsolutePath().normalize()));
        return List.copyOf(dirs);
    }

    /** What {@code dir}'s folder says. Blocking: a repository is opened, or an unpacked bot's bot.json read. */
    public static Facts read(Path dir, Map<Path, LocalDateTime> openedTimes) {
        LocalDateTime changed = lastModified(dir);
        LocalDateTime opened = openedTimes.get(dir.toAbsolutePath().normalize());
        LocalDateTime used = opened != null && opened.isAfter(changed) ? opened : changed;
        Optional<BotJson> bot = UnpackedBots.at(dir);
        if (bot.isPresent()) {
            return new Facts(Optional.of(BotInstaller.cloneUrl(bot.get().owner(), bot.get().repo())),
                    Optional.of(bot.get().tag()), true, used, Optional.of(bot.get().name()), true);
        }
        ProjectVcs vcs = new ProjectVcs(dir);
        return new Facts(Optional.ofNullable(vcs.remoteUrl(Remote.ORIGINAL)), vcs.nearestTag(),
                ProjectConfig.hasMain(dir), used, PomInfo.read(dir).name());
    }

    /** {@link ProjectManager#PROJECT_SHAPE}: a pom.xml and a src/main/java folder. */
    private static boolean isProject(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("src/main/java"));
    }

    private static LocalDateTime lastModified(Path dir) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(dir).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.MIN;
        }
    }
}
