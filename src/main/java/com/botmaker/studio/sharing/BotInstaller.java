package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionOrigin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs bots from their GitHub repos and says when a newer release is out (no account needed). Installing clones the author's
 * repository at the release tag into {@code ~/BotMakerProjects/} and records provenance ({@link BotSource});
 * a template is still unpacked from the release zip, since a project started from one is the user's own.
 *
 * <p>The blocking methods here are intended to run off the FX thread (the dialog wraps them in a
 * background task).
 */
public final class BotInstaller {

    private final GitHubClient client;
    private final GitHubGallery gallery;

    public BotInstaller(GitHubClient client, GitHubGallery gallery) {
        this.client = client;
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
     * the tag, and {@link BotSource} is written as the gallery's provenance record — one {@code INSTALL}
     * version, only when that (or the ignore file) changed anything. An update is then a real merge.
     *
     * @return the installed project directory
     * @throws IOException if the project already exists or the clone fails
     */
    public Path install(GalleryEntry entry, String tag) throws IOException {
        Path dest = installDir(entry.name());
        if (Files.exists(dest)) {
            throw new IOException("A project named '" + dest.getFileName() + "' already exists.");
        }
        try {
            ProjectVcs.cloneAt(cloneUrl(entry.owner(), entry.repo()), tag, dest, gallery.token());
            new BotSource(entry.owner(), entry.repo(), tag).write(dest);
            new ProjectVcs(dest).checkpoint(VersionOrigin.INSTALL, "Installed " + tag);
        } catch (IOException e) {
            deleteRecursively(dest);
            throw e;
        }
        return dest;
    }

    /** The HTTPS address a bot is cloned from: plain, with no token in it. */
    public static String cloneUrl(String owner, String repo) {
        return "https://github.com/" + owner + "/" + repo + ".git";
    }

    /**
     * Unpacks {@code entry} at release {@code tag} into {@code dest} as the <b>starting point of a new
     * project</b>, and deliberately writes <b>no</b> {@link BotSource}.
     *
     * <p>That absence is the whole difference from {@link #install}, and it is load-bearing. Provenance is
     * what makes a project an <em>installed bot</em>: it is what {@link #checkForUpdate} reads and what an
     * update merges from. A project created from a template is the user's from the second it lands — a later
     * release of the template is a different starting point, not a newer version of their bot — so there must
     * be nothing here for an update to find.
     *
     * @return the project directory
     */
    public Path unpackTemplate(GalleryEntry entry, String tag, Path dest) throws IOException {
        if (Files.exists(dest)) {
            throw new IOException("A project named '" + dest.getFileName() + "' already exists.");
        }
        downloadInto(entry.owner(), entry.repo(), tag, dest);
        // A template author may have installed their own template from the gallery while writing it; their
        // provenance file must not become the new project's.
        Files.deleteIfExists(dest.resolve(BotSource.FILE_NAME));
        return dest;
    }

    /**
     * The release an installed bot should move to, else empty — see {@link GalleryEntry#updateTarget}: a
     * Vetted bot is offered its vetted release, anything else the newest.
     *
     * @param catalog the gallery's current listings; a bot it does not name is offered its newest release
     */
    public Optional<String> checkForUpdate(Path projectDir, List<GalleryEntry> catalog) {
        Optional<BotSource> src = BotSource.read(projectDir);
        if (src.isEmpty()) return Optional.empty();
        String latest = gallery.latestReleaseTag(src.get().owner(), src.get().repo()).join();
        GalleryEntry listing = GitHubGallery.find(catalog, src.get().owner(), src.get().repo()).orElse(null);
        return GalleryEntry.updateTarget(listing, src.get().tag(), latest);
    }

    // update() — re-download the release and replace the project in place — was deleted on 2026-09-25: an
    // update is a real merge of the author's tag now, from the bot's Versions tab (39 §7).

    // -------------------------------------------------------------------------
    // Download + unzip
    // -------------------------------------------------------------------------

    private void downloadInto(String owner, String repo, String tag, Path dest) throws IOException {
        byte[] zip;
        try {
            zip = client.getBytes(GitHubConfig.archiveUrl(owner, repo, tag), gallery.token()).join();
        } catch (Exception e) {
            throw new IOException("Failed to download " + owner + "/" + repo + "@" + tag + ": "
                    + rootMessage(e), e);
        }
        Files.createDirectories(dest);
        unzipStrippingTopDir(zip, dest);
    }

    /**
     * Unzips a GitHub archive into {@code dest}, stripping the single top-level {@code repo-tag/} directory
     * that GitHub wraps every entry in. Guards against zip-slip.
     */
    private static void unzipStrippingTopDir(byte[] zipBytes, Path dest) throws IOException {
        Path destRoot = dest.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String stripped = stripFirstSegment(entry.getName());
                if (stripped.isEmpty()) continue; // the top-level dir entry itself

                Path target = destRoot.resolve(stripped).normalize();
                if (!target.startsWith(destRoot)) {
                    throw new IOException("Blocked unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static String stripFirstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(slash + 1);
    }

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

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
