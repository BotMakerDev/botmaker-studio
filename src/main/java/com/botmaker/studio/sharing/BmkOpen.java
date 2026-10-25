package com.botmaker.studio.sharing;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Opening a {@code .bmk} (2026-10-25, spec § 2 "Opening one"): checked, trusted, unpacked into
 * {@link UnpackedBots#dirOf}, its Maven Central jars fetched and checked. No UI: each step that needs the user is a
 * {@link Result} the caller answers, then calls again.
 *
 * <ol>
 *   <li>The file's names and {@code bot.json} are read ({@link BotFile#peek}).</li>
 *   <li>When the catalog pins a {@code .bmk} for that {@code owner/repo} and tag, the file must be that one.</li>
 *   <li>A tag already unpacked and checked from this very file opens straight away.</li>
 *   <li>Otherwise the user agrees first: always for a file the catalog does not pin, once per {@code owner/repo}
 *       for one it does.</li>
 *   <li>Unpacked ({@link BotFile#read} checks each packed jar), then every jar it only lists is fetched by its
 *       coordinate and checked against its sha256, with this system's JavaFX natives
 *       ({@link UnpackedBots#natives}). Offline, the folder is kept and the next open finishes it.</li>
 * </ol>
 */
public final class BmkOpen {

    private BmkOpen() {}

    /**
     * The {@code .bmk} the command line asks to open: {@code --open=<file>}, else the first bare argument naming a
     * {@code .bmk} — a path, or a {@code file:} URI — which is how a double-click arrives (the Linux menu entry's
     * {@code %f}, the Windows file association's {@code "%1"}).
     */
    public static Optional<Path> requested(Optional<String> named, List<String> unnamed) {
        if (named.isPresent()) return Optional.of(Path.of(named.get()).toAbsolutePath().normalize());
        for (String arg : unnamed) {
            if (!arg.toLowerCase(Locale.ROOT).endsWith(".bmk")) continue;
            try {
                Path file = arg.startsWith("file:") ? Path.of(URI.create(arg)) : Path.of(arg);
                return Optional.of(file.toAbsolutePath().normalize());
            } catch (IllegalArgumentException | FileSystemNotFoundException e) {   // InvalidPathException too
                // not a path this system reads: the next argument may be
            }
        }
        return Optional.empty();
    }

    /** What an open came to. */
    public sealed interface Result permits Refused, NeedsTrust, Opened {}

    /** Not opened, and why, in a sentence for the user. */
    public record Refused(String why) implements Result {}

    /**
     * The user must agree before this bot is unpacked: {@code listed} when the catalog pins this very file, with its
     * row; a file it does not pin gets the stronger warning.
     */
    public record NeedsTrust(BotJson bot, Optional<GalleryEntry> entry, boolean listed) implements Result {

        /** What the trust sheet says. */
        public String warning() {
            String who = "“" + bot.name() + "” by " + bot.owner();
            if (listed) {
                return who + " (" + entry.map(e -> e.trust().displayName()).orElse("Community")
                        + ") runs code on this computer.";
            }
            return who + " runs code on this computer. Nobody checked this file. Only open a bot from someone you "
                    + "trust.";
        }

        /** The key a trusted open is remembered by ({@link #trustKey}). */
        public String key() {
            return trustKey(bot);
        }
    }

    /** Unpacked and checked in {@code dir}: run it. */
    public record Opened(Path dir, BotJson bot) implements Result {}

    /** Gets jars by coordinate alone ({@link MavenService#fetch}); a test's answers without a network. */
    @FunctionalInterface
    public interface Fetcher {
        MavenService.Fetched fetch(List<String> coordinates);
    }

    /** {@code owner/repo}, lower case: one bot, whatever case a file spells it in. */
    public static String trustKey(BotJson bot) {
        return (bot.owner() + "/" + bot.repo()).toLowerCase(Locale.ROOT);
    }

    /**
     * Opens {@code bmk} under {@code root}. Blocking (it unpacks and may download): off the FX thread.
     *
     * @param catalog the catalog as last read, for the file's pinned sha256
     * @param trusted the {@link #trustKey}s the user agreed to before, for files the catalog pins
     * @param agreed  whether the user just agreed to this file ({@link NeedsTrust})
     */
    public static Result open(Path bmk, CatalogIndex catalog, Set<String> trusted, boolean agreed, Path root,
                              Fetcher fetch) {
        try {
            BotJson bot = BotFile.peek(bmk);
            Optional<GalleryEntry> entry = catalog.bots().stream()
                    .filter(e -> (e.owner() + "/" + e.repo()).toLowerCase(Locale.ROOT).equals(trustKey(bot)))
                    .findFirst();
            Optional<String> pinned = entry.flatMap(e -> e.bmkShaOf(bot.tag()));
            String sha = BotFiles.sha256(bmk);
            if (pinned.isPresent() && !pinned.get().equalsIgnoreCase(sha)) {
                return new Refused("This file is not the one the catalog lists for " + bot.name() + " " + bot.tag()
                        + ": it was changed, or made from another commit.");
            }
            Path dir = UnpackedBots.dirOf(root, bot);
            // One open at a time: two of one bot (a drop and --open) would unpack over each other.
            synchronized (BmkOpen.class) {
                // Only this very file's folder opens straight away: another file of the same tag (one the user
                // agreed to once, unchecked) is replaced by this one, through the trust sheet again.
                if (UnpackedBots.at(root, dir).isPresent() && sha.equals(checkedSha(dir))) return new Opened(dir, bot);
                if (!agreed && !(pinned.isPresent() && trusted.contains(trustKey(bot)))) {
                    return new NeedsTrust(bot, entry, pinned.isPresent());
                }
                // A folder an earlier open left half done, or another file's: unpacked again from this one.
                deleteTree(dir);
                BotFile.read(bmk, dir);
                return finish(dir, bot, sha, fetch);
            }
        } catch (BotFile.Refused e) {
            return new Refused("This file cannot be opened: " + e.getMessage());
        } catch (IOException e) {
            return new Refused("Could not open " + bmk.getFileName() + ": " + e.getMessage());
        }
    }

    /** The listed jars fetched and checked, then {@link UnpackedBots#CHECKED} written. */
    private static Result finish(Path dir, BotJson bot, String sha, Fetcher fetch) throws IOException {
        List<BotJson.Lib> listed = bot.libs().stream().filter(l -> l.packed().isEmpty()).toList();
        List<String> wanted = new ArrayList<>(listed.stream().map(BotJson.Lib::coordinate).toList());
        wanted.addAll(UnpackedBots.natives(bot));
        MavenService.Fetched fetched = wanted.isEmpty() ? new MavenService.Fetched(Map.of(), List.of())
                : fetch.fetch(wanted);
        if (!fetched.missing().isEmpty()) {
            String names = fetched.missing().stream().map(c -> c.split(":")[1]).distinct()
                    .collect(Collectors.joining(", "));
            return new Refused(bot.name() + " needs a connection once, to fetch " + names
                    + ". Connect to the internet and open it again.");
        }
        for (BotJson.Lib lib : listed) {
            Path file = fetched.files().get(lib.coordinate());
            if (file == null || !BotFiles.sha256(file).equalsIgnoreCase(lib.sha256())) {
                return new Refused(lib.coordinate() + " from Maven Central is not the jar " + bot.name()
                        + " was made with, so it was not opened.");
            }
        }
        Files.writeString(dir.resolve(UnpackedBots.CHECKED), sha + "\n");
        return new Opened(dir, bot);
    }

    /** The sha256 of the file {@code dir} was unpacked from, as {@link UnpackedBots#CHECKED} holds it; blank if none. */
    private static String checkedSha(Path dir) {
        try {
            return Files.readString(dir.resolve(UnpackedBots.CHECKED)).strip();
        } catch (IOException e) {
            return "";
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
