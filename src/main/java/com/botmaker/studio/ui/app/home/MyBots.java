package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.sharing.GalleryEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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

    /**
     * What one project's folder says, read off the disk ({@link #read}).
     *
     * @param originalUrl the {@code original} remote's URL: the author's repository a bot was got from
     * @param version     the nearest tag, empty for a bot never released
     * @param runnable    whether a class holds {@code main}; a project that is no bot is only edited
     * @param lastUsed    when Studio last opened it, else when its folder last changed
     */
    public record Facts(Optional<String> originalUrl, Optional<String> version, boolean runnable,
                        LocalDateTime lastUsed) {}

    /**
     * One card. {@code owner} is the author of a bot got from someone, empty for the user's own ("mine");
     * {@code available} is the catalog's newer tag.
     */
    public record MyBot(Path dir, String name, Optional<String> owner, Optional<String> version,
                        LocalDateTime lastUsed, Optional<String> available, boolean runnable, State state) {}

    /**
     * The cards for {@code projectDirs}, most recently used first, gone ones last. {@code facts} is asked only of
     * a folder that is there; {@code latestTagByRepo} is the catalog's tag by lower-case {@code owner/repo}.
     */
    public static List<MyBot> scan(List<Path> projectDirs, Function<Path, Facts> facts,
                                   Map<String, String> latestTagByRepo) {
        List<MyBot> bots = new ArrayList<>();
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
            Optional<String> available = original
                    .map(s -> latestTagByRepo.get(key(s.owner(), s.repo())))
                    .flatMap(latest -> GalleryEntry.updateTarget(f.version().orElse(""), latest));
            bots.add(new MyBot(dir, name, original.map(SyncModel.Slug::owner), f.version(), f.lastUsed(),
                    available, f.runnable(), State.READY));
        }
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
     * their card can say so. Each folder once.
     */
    public static List<Path> folders(ProjectManager projects, List<Path> remembered) {
        Set<Path> dirs = new LinkedHashSet<>();
        projects.listProjects().forEach(p -> dirs.add(p.projectPath().toAbsolutePath().normalize()));
        for (Path dir : remembered) {
            Path d = dir.toAbsolutePath().normalize();
            if (!projects.isUnderRoot(d)) dirs.add(d);
        }
        return List.copyOf(dirs);
    }

    /** What {@code dir}'s folder says. Blocking: a repository is opened. */
    public static Facts read(Path dir, Map<Path, LocalDateTime> openedTimes) {
        ProjectVcs vcs = new ProjectVcs(dir);
        LocalDateTime changed = lastModified(dir);
        LocalDateTime opened = openedTimes.get(dir.toAbsolutePath().normalize());
        LocalDateTime used = opened != null && opened.isAfter(changed) ? opened : changed;
        return new Facts(Optional.ofNullable(vcs.remoteUrl(Remote.ORIGINAL)), vcs.nearestTag(),
                ProjectConfig.hasMain(dir), used);
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
