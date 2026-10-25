package com.botmaker.studio.sharing;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <i>Edit in Studio</i> on a bot opened from a {@code .bmk} (spec § 1a, the user's call 2026-10-25). The unpacked
 * folder is for running only, so the author's repository is cloned at the file's tag and commit into the projects
 * folder, linked as <i>Get</i> links it (remote {@code original}): updates merge, <i>Save to my copy</i> works. When
 * the repository cannot be reached (a private or unlisted file, offline), the unpacked sources are copied there
 * instead, with a history started and no {@code original}.
 */
public final class BmkEdit {

    private BmkEdit() {}

    /** How a project is cloned from a bot's repository: {@link BotInstaller#install(GalleryEntry, String, Path)}. */
    @FunctionalInterface
    public interface Cloner {
        Path clone(GalleryEntry entry, String tag, Path dest) throws IOException;
    }

    /**
     * The project made: {@code linked} when it is a clone of the author's repository, else why it is a copy.
     */
    public record Edited(Path dir, boolean linked, String why) {}

    /**
     * The editable project for the unpacked bot in {@code unpacked}: one of {@code projects} already cloned from its
     * author's repository (remote {@code original}), else a new one under {@code projectsRoot}, in a folder named
     * after the bot. Blocking (it clones): off the FX thread.
     *
     * @throws IOException when even the copy fails
     */
    public static Edited edit(Path unpacked, BotJson bot, List<Path> projects, Path projectsRoot, Cloner cloner)
            throws IOException {
        for (Path project : projects) {
            Optional<SyncModel.Slug> original = Optional.ofNullable(new ProjectVcs(project).remoteUrl(Remote.ORIGINAL))
                    .flatMap(SyncModel::slug);
            if (original.isPresent() && original.get().owner().equalsIgnoreCase(bot.owner())
                    && original.get().repo().equalsIgnoreCase(bot.repo())) {
                return new Edited(project, true, "");
            }
        }
        Path dest = freeFolder(projectsRoot, BotInstaller.sanitizeName(bot.name()));
        // The catalog's pin for an install is the commit; here the file names its own, so the clone is that commit.
        GalleryEntry entry = new GalleryEntry(bot.repo(), bot.owner(), bot.repo(), "", List.of(), "", bot.tag(),
                PublisherRank.COMMUNITY, 0, List.of(), PublisherRank.COMMUNITY,
                List.of(new Catalog.Bot.Release(bot.tag(), bot.commit(), List.of())));
        try {
            return new Edited(cloner.clone(entry, bot.tag(), dest), true, "");
        } catch (IOException cloneFailed) {
            copySources(unpacked, dest);
            new ProjectVcs(dest).init();
            return new Edited(dest, false, "The author's repository " + bot.owner() + "/" + bot.repo()
                    + " could not be reached (" + cloneFailed.getMessage() + "), so this is a copy of the file's "
                    + "sources: it does not get the author's updates.");
        }
    }

    /** {@code name}, else {@code name2}, {@code name3}…: the first that is not a folder yet. */
    static Path freeFolder(Path root, String name) {
        Path dest = root.resolve(name);
        for (int n = 2; Files.exists(dest); n++) dest = root.resolve(name + n);
        return dest;
    }

    /** The bot's own files — pom, icon, sources, resources — without the jars, {@code bot.json} or the marks. */
    private static void copySources(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = from.relativize(file).toString().replace('\\', '/');
                if (name.startsWith("lib/") || name.equals(BotFile.BOT_JSON) || name.startsWith(".")
                        || name.startsWith("target/")) continue;
                Path target = to.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
