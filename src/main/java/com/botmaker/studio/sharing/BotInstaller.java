package com.botmaker.studio.sharing;

import com.botmaker.studio.config.Constants;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * Installs bots from their GitHub repos and says when a newer release is out (no account needed). Installing
 * clones the author's repository at the release tag into {@code ~/BotMakerProjects/}; the clone's remote
 * {@code original} is the whole record of where it came from ({@link BotSource}, 2026-10-23). Browse Bots'
 * Install and New Project's bot rows both clone here (2026-10-23): a project started from a bot stays linked to
 * its author, so their later releases merge in.
 *
 * <p>The blocking methods here are intended to run off the FX thread (the dialog wraps them in a
 * background task).
 */
public final class BotInstaller {

    private final GitHubGallery gallery;

    public BotInstaller(GitHubGallery gallery) {
        this.gallery = gallery;
    }

    /** The directory a bot named {@code name} would install into (sanitised to a valid project name). */
    public Path installDir(String name) {
        return Constants.PROJECTS_ROOT.resolve(sanitizeName(name));
    }

    public boolean isInstalled(String name) {
        return Files.exists(installDir(name));
    }

    /**
     * Installs {@code entry} at release {@code tag} as a <b>clone</b> of its author's repository
     * ({@code docs/refactor/39-versions.md} §7): the remote is {@code original}, branch {@code main} starts at
     * the tag — one {@code INSTALL} version, only when the ignore file changed anything. An update is then a
     * real merge.
     *
     * @return the installed project directory
     * @throws IOException if the project already exists or the clone fails
     */
    public Path install(GalleryEntry entry, String tag) throws IOException {
        return install(entry, tag, installDir(entry.name()));
    }

    /**
     * {@link #install(GalleryEntry, String)} into {@code dest}, a folder named as New Project's user chose. Only
     * the folder takes their name: the bot keeps its author's package and classes, since a rename would make
     * every later update conflict ({@code ProjectConfig.forDirectory} reads the package off {@code main}).
     */
    public Path install(GalleryEntry entry, String tag, Path dest) throws IOException {
        if (Files.exists(dest)) {
            throw new IOException("A project named '" + dest.getFileName() + "' already exists.");
        }
        try {
            try {
                ProjectVcs.cloneAt(cloneUrl(entry.owner(), entry.repo()), tag, dest, gallery.token());
            } catch (IOException cloneFailed) {
                // The gallery's copy of a removed repository went with the gallery (2026-10-23): say why instead.
                if (authorGone(entry.owner(), entry.repo())) {
                    throw new IOException("The repository " + entry.slug() + " was removed by its author, so "
                            + "it can no longer be installed.", cloneFailed);
                }
                throw cloneFailed;
            }
            new ProjectVcs(dest).checkpoint(VersionOrigin.INSTALL, "Installed " + tag);
        } catch (IOException e) {
            deleteRecursively(dest);
            throw e;
        }
        return dest;
    }

    // installFromMirror() — a removed repository unpacked from the gallery's copy — went on 2026-10-23 with the
    // gallery repository.

    /** Whether GitHub says {@code owner/repo} no longer exists. Blocking; false when GitHub cannot be asked. */
    public boolean authorGone(String owner, String repo) {
        try {
            return gallery.repositoryGone(owner, repo).join();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the installed bot at {@code projectDir} lost its author's repository: it then still runs, and
     * keeps its own history, but has no original to take an update from. Blocking.
     */
    public boolean authorGone(Path projectDir) {
        return BotSource.read(projectDir).map(s -> authorGone(s.owner(), s.repo())).orElse(false);
    }

    /** The HTTPS address a bot is cloned from: plain, with no token in it. */
    public static String cloneUrl(String owner, String repo) {
        return "https://github.com/" + owner + "/" + repo + ".git";
    }

    // unpackTemplate() — a release unzipped with no provenance, renamed into the user's package — went on
    // 2026-10-23 with templates: New Project clones through install() now.

    /**
     * The release an installed bot should move to — the one the catalog lists, never a newer one no check passed
     * (2026-10-23) — else empty ({@link GalleryEntry#updateTarget}).
     */
    public Optional<String> checkForUpdate(Path projectDir) {
        Optional<BotSource> src = BotSource.read(projectDir);
        if (src.isEmpty()) return Optional.empty();
        String latest = gallery.listedTag(src.get().owner(), src.get().repo()).join();
        return GalleryEntry.updateTarget(src.get().tag(), latest);
    }

    // update() — re-download the release and replace the project in place — was deleted on 2026-09-25: an
    // update is a real merge of the author's tag now, from the bot's Versions tab (39 §7).

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Best-effort coercion of an arbitrary bot/repo name to a valid project name ({@code ^[A-Z][a-zA-Z0-9]*$}),
     * camel-casing across separators (e.g. {@code clicker-bot} → {@code ClickerBot}).
     */
    static String sanitizeName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upNext = true; // capitalise the first kept letter and any letter after a separator
        for (char c : (name == null ? "" : name).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upNext ? Character.toUpperCase(c) : c);
                upNext = false;
            } else {
                upNext = true;
            }
        }
        String cleaned = sb.toString();
        if (cleaned.isEmpty()) return "ImportedBot";
        if (!Character.isLetter(cleaned.charAt(0))) cleaned = "Bot" + cleaned;
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }
}
