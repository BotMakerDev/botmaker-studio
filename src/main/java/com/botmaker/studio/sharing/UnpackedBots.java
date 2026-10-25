package com.botmaker.studio.sharing;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.shared.github.SemVer;
import com.botmaker.shared.tools.UserDirs;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.ProgressReporter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The bots opened from a {@code .bmk} (2026-10-25, spec § 2): each tag unpacked once into
 * {@code <data>/bots/<owner>/<repo>/<tag>/}, for running only — no {@code .git}, never edited ({@link BmkEdit} clones
 * the author's repository instead). Such a folder runs with the jars its {@code bot.json} lists, not with what its pom
 * would resolve to ({@link #classpath}).
 *
 * <p>A folder counts once {@link BmkOpen} has checked every jar in it and written {@link #CHECKED}; one an open left
 * half done (offline) is finished by the next open, and is no bot until then.
 */
public final class UnpackedBots {

    /** Written last by {@link BmkOpen}: the folder's jars were all checked. Holds the {@code .bmk}'s sha256. */
    public static final String CHECKED = ".botmaker-checked";

    private static final ObjectMapper JSON = new ObjectMapper();

    private UnpackedBots() {}

    /** Where this computer keeps them. */
    public static Path root() {
        return UserDirs.data().resolve("bots");
    }

    /** Where {@code bot}'s tag is unpacked under {@code root}. */
    public static Path dirOf(Path root, BotJson bot) {
        return root.resolve(bot.owner()).resolve(bot.repo()).resolve(bot.tag());
    }

    /** {@code dir}'s {@code bot.json}, when {@code dir} is a checked unpacked bot under {@link #root()}. */
    public static Optional<BotJson> at(Path dir) {
        return at(root(), dir);
    }

    /**
     * {@code dir}'s {@code bot.json}, when {@code dir} is a checked unpacked bot under {@code root}; empty for any
     * other folder. Only {@code root/<owner>/<repo>/<tag>} counts, with those three names the ones its {@code bot.json}
     * says: a project cloned from someone's repository may ship a {@code bot.json} and a marker of its own, and must
     * not pass for a bot this computer checked (its classpath and its author would be the file's word).
     */
    public static Optional<BotJson> at(Path root, Path dir) {
        Path base = root.toAbsolutePath().normalize();
        Path tag = dir.toAbsolutePath().normalize();
        if (!tag.startsWith(base) || base.relativize(tag).getNameCount() != 3) return Optional.empty();
        Path json = tag.resolve(BotFile.BOT_JSON);
        if (!Files.isRegularFile(tag.resolve(CHECKED)) || !Files.isRegularFile(json)) return Optional.empty();
        try {
            BotJson bot = JSON.readValue(json.toFile(), BotJson.class);
            return dirOf(base, bot).equals(tag) ? Optional.of(bot) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code dir} is a checked unpacked bot under {@link #root()}. */
    public static boolean isUnpacked(Path dir) {
        return at(dir).isPresent();
    }

    /**
     * Each bot's newest checked tag under {@code root}, one folder per {@code owner/repo}: what My bots lists. An
     * older tag stays on disk beside it (spec, review focus 1) and is no card.
     */
    public static List<Path> newest(Path root) {
        List<Path> out = new ArrayList<>();
        for (Path repo : children(root).flatMap(UnpackedBots::children).toList()) {
            children(repo).filter(tag -> at(root, tag).isPresent()).max(NEWEST_LAST).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Deletes the bot whose tag {@code dir} is, every tag of it, from {@link #root()}; refused for a folder outside
     * it or no unpacked bot. The runner's values are kept elsewhere, so a later open gets them back.
     */
    public static void remove(Path dir) throws IOException {
        Path root = root().toAbsolutePath().normalize();
        Path tag = dir.toAbsolutePath().normalize();
        if (!isUnpacked(tag) || !tag.startsWith(root) || root.relativize(tag).getNameCount() != 3) {
            throw new IOException(dir + " is not a bot opened from a file.");
        }
        BmkOpen.deleteTree(tag.getParent());
        try (Stream<Path> left = Files.list(tag.getParent().getParent())) {
            if (left.findAny().isEmpty()) Files.delete(tag.getParent().getParent());
        }
    }

    /** By tag: semantic versions compared as versions, others by name, and a version after any other name. */
    static final Comparator<Path> NEWEST_LAST = (a, b) -> {
        String x = a.getFileName().toString();
        String y = b.getFileName().toString();
        boolean vx = SemVer.isValid(x);
        boolean vy = SemVer.isValid(y);
        if (vx && vy) return SemVer.compare(x, y);
        if (vx != vy) return vx ? 1 : -1;
        return x.compareTo(y);
    };

    private static Stream<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) return Stream.empty();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    /**
     * The classpath of {@code dir} when it is an unpacked bot ({@link MavenService#resolve} asks first): empty for
     * any other project.
     */
    public static Optional<MavenService.Resolution> classpath(Path dir, ProgressReporter progress) {
        return at(dir).map(bot -> classpath(dir, bot, coordinates -> MavenService.fetch(coordinates, progress)));
    }

    /**
     * {@code bot}'s jars in the order it lists them: each packed one from {@code dir/lib/}, each other one
     * {@code fetch}ed by its coordinate alone, and this system's JavaFX natives ({@link #natives}). A jar not got is
     * a problem line, as an unresolved dependency is.
     */
    static MavenService.Resolution classpath(Path dir, BotJson bot,
                                             Function<List<String>, MavenService.Fetched> fetch) {
        List<String> wanted = new ArrayList<>();
        for (BotJson.Lib lib : bot.libs()) if (lib.packed().isEmpty()) wanted.add(lib.coordinate());
        wanted.addAll(natives(bot));
        MavenService.Fetched fetched = wanted.isEmpty() ? new MavenService.Fetched(Map.of(), List.of())
                : fetch.apply(wanted);
        List<String> jars = new ArrayList<>();
        for (BotJson.Lib lib : bot.libs()) {
            Optional<Path> file = lib.packed().map(dir::resolve).or(() -> Optional.ofNullable(
                    fetched.files().get(lib.coordinate())));
            file.ifPresent(f -> jars.add(f.toAbsolutePath().toString()));
        }
        for (String nativeJar : natives(bot)) {
            Path file = fetched.files().get(nativeJar);
            if (file != null) jars.add(file.toAbsolutePath().toString());
        }
        List<String> problems = fetched.missing().stream().map(c -> c + " — not found").toList();
        return new MavenService.Resolution(jars, problems);
    }

    /**
     * The native jars a {@code .bmk} leaves out and this system needs: JavaFX's, one per {@code org.openjfx} jar it
     * lists, with this system's classifier (as JavaFX's own pom picks it). Fetched as the other Central jars are,
     * not checked against {@code bot.json}, which cannot list another system's.
     */
    static List<String> natives(BotJson bot) {
        String classifier = javafxClassifier(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        return bot.libs().stream()
                .filter(l -> l.groupId().equals("org.openjfx") && l.classifier().isEmpty() && l.packed().isEmpty())
                .map(l -> l.coordinate() + ":" + classifier).toList();
    }

    /** JavaFX's classifier for a system: {@code linux}, {@code linux-aarch64}, {@code win}, {@code mac}, {@code mac-aarch64}. */
    static String javafxClassifier(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        boolean arm = osArch.equals("aarch64") || osArch.equals("arm64");
        if (os.contains("win")) return "win";
        if (os.contains("mac")) return arm ? "mac-aarch64" : "mac";
        return arm ? "linux-aarch64" : "linux";
    }
}
