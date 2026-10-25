package com.botmaker.studio.sharing;

import com.botmaker.shared.platform.Os;
import com.botmaker.shared.tools.UserDirs;
import com.botmaker.studio.config.AppPaths;
import com.botmaker.studio.project.BotIcon;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Each bot of My bots as an entry of the computer's programs menu (spec § 5): clicking it starts
 * {@code botmaker-studio --run=<owner/repo or folder>}, straight to the bot's runner window. One record per system,
 * each owning one place it writes nothing else into, so {@link #sync} can remove what My bots no longer lists:
 * Linux {@code botmaker-bot-*.desktop} in {@code ~/.local/share/applications}, Windows the Start menu folder
 * {@value #FOLDER}, macOS {@code ~/Applications/}{@value #FOLDER}. Icons are converted from the bot's
 * {@code icon.png} (or its letter) into {@code menu-icons/} of the user data folder.
 *
 * <p>Only an installed Studio writes entries ({@link #forThisComputer}): a development run has no launcher for the
 * menu to start.
 */
public sealed interface ProgramEntries permits ProgramEntries.Linux, ProgramEntries.Windows, ProgramEntries.Mac {

    /** The folder of the programs menu that holds the bots, on Windows and macOS. */
    String FOLDER = "BotMaker bots";

    /** The macOS bundle identifier of Studio (the pom's {@code macPackageIdentifier}). */
    String STUDIO_BUNDLE = "com.botmaker.studio";

    /**
     * One bot's entry. {@code id} names its files ({@link #idOf}); {@code run} is what {@code --run=} takes;
     * {@code icon} the bot's {@code icon.png}, the letter of {@code title} when empty.
     */
    record Entry(String id, String title, String run, Optional<Path> icon) {

        /** The entry of a bot whose author's repository is {@code owner/repo}. */
        public static Entry ofRepo(String owner, String repo, String title, Path dir) {
            // The hash keeps apart two repositories idOf would spell alike (a/b-c and a-b/c, x_y and x-y).
            String slug = (owner + "/" + repo).toLowerCase(Locale.ROOT);
            return new Entry(idOf(owner + "-" + repo + "-" + Integer.toHexString(slug.hashCode())), title,
                    owner + "/" + repo, BotIcon.of(dir));
        }

        /** The entry of a bot of the user's own, run by its folder. */
        public static Entry ofFolder(Path dir, String title) {
            Path abs = dir.toAbsolutePath().normalize();
            String folder = abs.getFileName() == null ? "bot" : abs.getFileName().toString();
            String hash = Integer.toHexString(abs.toString().hashCode());
            return new Entry(idOf("local-" + folder + "-" + hash), title, abs.toString(), BotIcon.of(abs));
        }
    }

    /** {@code text} as a file name and a bundle id can both hold it: lower case, letters, digits, {@code .} and {@code -}. */
    static String idOf(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]+", "-");
    }

    /** The files {@code entry} is made of, under this system's places. */
    List<Path> files(Entry entry, String fileName);

    /** Writes {@code entry} as {@code fileName} (its title, made a file name and unique), replacing what was there. */
    void write(Entry entry, String fileName) throws IOException;

    /** What this system's places hold now: each entry's top file or folder. */
    List<Path> existing() throws IOException;

    /**
     * Makes the menu hold exactly {@code wanted}: each written, and every entry of ours it does not name removed.
     * Best effort per entry: one that fails is skipped and the others are made.
     */
    default void sync(List<Entry> wanted) throws IOException {
        // One sync at a time: two My bots reads overlap (a switch flipped during the first), and one's removals
        // would otherwise take what the other just wrote.
        synchronized (SYNC) {
            Map<Entry, String> names = fileNames(wanted);
            Set<String> keep = new HashSet<>();
            for (Map.Entry<Entry, String> e : names.entrySet()) {
                files(e.getKey(), e.getValue()).forEach(p -> keep.add(key(p)));
                try {
                    write(e.getKey(), e.getValue());
                } catch (IOException | RuntimeException ex) {
                    System.err.println("Could not add " + e.getKey().title() + " to the programs menu: "
                            + ex.getMessage());
                }
            }
            for (Path old : existing()) if (!keep.contains(key(old))) delete(old);
            for (Path icon : listed(icons())) if (!keep.contains(key(icon))) Files.deleteIfExists(icon);
        }
    }

    /** The lock {@link #sync} holds. */
    Object SYNC = new Object();

    /** A path compared without case: Windows and macOS folders hold Foo.app and foo.app as one. */
    private static String key(Path path) {
        return path.toString().toLowerCase(Locale.ROOT);
    }

    /** Where this system's converted icons are kept. */
    Path icons();

    /**
     * The menu's name of each entry: its title without the characters a file name cannot hold, the second of two
     * equal names followed by its id. In {@code id} order, so the same bots always get the same names.
     */
    static Map<Entry, String> fileNames(List<Entry> wanted) {
        Map<Entry, String> names = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        wanted.stream().sorted(Comparator.comparing(Entry::id)).forEach(e -> {
            // Windows also drops a trailing dot or space and refuses its device names (CON, NUL, COM1…).
            String name = e.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("[. ]+$", "").strip();
            if (name.isEmpty() || name.startsWith(".")) name = e.id();
            if (name.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?")) name = name + " bot";
            if (!taken.add(name.toLowerCase(Locale.ROOT))) {
                name = name + " (" + e.id() + ")";
                taken.add(name.toLowerCase(Locale.ROOT));
            }
            names.put(e, name);
        });
        return names;
    }

    /**
     * This computer's entries, for the Studio installed at {@code jpackage.app-path} (set by jpackage's launcher);
     * empty in a development run, or on a system with no programs menu we know.
     */
    static Optional<ProgramEntries> forThisComputer() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || launcher.isBlank()) return Optional.empty();
        // A sandbox (AppPaths) stands for the home, so a test's entries land in it and not in the real menu.
        boolean sandboxed = AppPaths.sandbox().isPresent();
        Path home = AppPaths.entriesHome();
        Path icons = UserDirs.data().resolve("menu-icons");
        Os os = Os.current();
        if (os.isLinux()) {
            String dataHome = sandboxed ? null : System.getenv("XDG_DATA_HOME");
            Path data = dataHome != null && !dataHome.isBlank() ? Path.of(dataHome) : home.resolve(".local/share");
            return Optional.of(new Linux(data.resolve("applications"), icons, launcher));
        }
        if (os.isWindows()) {
            String appData = sandboxed ? home.resolve("AppData").toString() : System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) return Optional.empty();
            return Optional.of(new Windows(Windows.startMenu(Path.of(appData)), icons, launcher));
        }
        if (os.isMac()) return Optional.of(new Mac(home.resolve("Applications").resolve(FOLDER), icons));
        return Optional.empty();
    }

    /** {@code entry}'s icon as a PNG of {@code side} pixels: its {@code icon.png} scaled, else its letter. */
    static byte[] png(Entry entry, int side) throws IOException {
        BufferedImage source = null;
        if (entry.icon().isPresent()) {
            try {
                source = ImageIO.read(entry.icon().get().toFile());
            } catch (IOException e) {
                source = null;   // a broken icon.png shows the letter, as the cards do
            }
        }
        if (source == null) source = BotIcon.letter(entry.title(), side);
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", bytes)) throw new IOException("No PNG writer.");
        return bytes.toByteArray();
    }

    /** A Windows icon file holding one 256-pixel PNG (Vista and later read PNG entries). */
    static byte[] ico(byte[] png) {
        ByteBuffer b = ByteBuffer.allocate(6 + 16 + png.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 1);          // reserved, type icon, one image
        b.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);              // 256 × 256, no palette
        b.putShort((short) 1).putShort((short) 32).putInt(png.length).putInt(6 + 16);
        return b.put(png).array();
    }

    /** A macOS icon file holding one 256-pixel PNG, as its {@code ic08} element. */
    static byte[] icns(byte[] png) {
        ByteBuffer b = ByteBuffer.allocate(8 + 8 + png.length).order(ByteOrder.BIG_ENDIAN);
        b.put("icns".getBytes(StandardCharsets.US_ASCII)).putInt(8 + 8 + png.length);
        b.put("ic08".getBytes(StandardCharsets.US_ASCII)).putInt(8 + png.length);
        return b.put(png).array();
    }

    /** Writes {@code bytes} unless the file already holds them. */
    static void writeIfChanged(Path file, byte[] bytes) throws IOException {
        if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes)) return;
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static List<Path> listed(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    private static void delete(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.walk(path)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    /** {@code ~/.local/share/applications/botmaker-bot-<id>.desktop}, its PNG in {@code icons}. */
    record Linux(Path applications, Path icons, String launcher) implements ProgramEntries {

        static final String PREFIX = "botmaker-bot-";

        @Override
        public List<Path> files(Entry entry, String fileName) {
            return List.of(applications.resolve(PREFIX + entry.id() + ".desktop"), icons.resolve(entry.id() + ".png"));
        }

        @Override
        public void write(Entry entry, String fileName) throws IOException {
            List<Path> files = files(entry, fileName);
            writeIfChanged(files.get(1), png(entry, BotIcon.SIZE));
            writeIfChanged(files.get(0), desktop(entry, files.get(1)).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public List<Path> existing() throws IOException {
            return listed(applications).stream().filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(".desktop");
            }).toList();
        }

        /** The {@code .desktop} file's text (Desktop Entry Specification 1.5). */
        String desktop(Entry entry, Path icon) {
            String title = value(entry.title());
            return "[Desktop Entry]\n"
                    + "Type=Application\n"
                    + "Name=" + title + "\n"
                    + "Comment=Run " + title + " with BotMaker\n"
                    + "Exec=" + value(execArg(launcher) + " " + execArg("--run=" + entry.run())) + "\n"
                    + "Icon=" + value(icon.toString()) + "\n"
                    + "Terminal=false\n"
                    + "Categories=Utility;\n";
        }

        /** One argument of {@code Exec}, quoted when it holds a reserved character, its {@code %} doubled. */
        static String execArg(String arg) {
            String escaped = arg.replace("%", "%%");
            if (!escaped.matches(".*[\\s\"'\\\\><~|&;$*?#()`].*")) return escaped;
            return "\"" + escaped.replaceAll("([\"`$\\\\])", "\\\\$1") + "\"";
        }

        /** A string value: backslash and line breaks escaped. */
        static String value(String text) {
            return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    /** {@code Start Menu\Programs\BotMaker bots\<title>.lnk}, made by PowerShell, its {@code .ico} in {@code icons}. */
    record Windows(Path folder, Path icons, String launcher) implements ProgramEntries {

        /** The Start menu folder of the bots under {@code %APPDATA%}. */
        static Path startMenu(Path appData) {
            return appData.resolve("Microsoft").resolve("Windows").resolve("Start Menu").resolve("Programs")
                    .resolve(FOLDER);
        }

        /** The shortcut, its icon, and what it was made with: a moved Studio or a new icon makes it again. */
        @Override
        public List<Path> files(Entry entry, String fileName) {
            return List.of(folder.resolve(fileName + ".lnk"), icons.resolve(entry.id() + ".ico"),
                    icons.resolve(entry.id() + ".lnk-made"));
        }

        @Override
        public void write(Entry entry, String fileName) throws IOException {
            List<Path> files = files(entry, fileName);
            byte[] ico = ico(png(entry, BotIcon.SIZE));
            byte[] made = (launcher + "\n" + arguments(entry) + "\n" + entry.title() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            boolean same = Files.isRegularFile(files.get(1)) && Arrays.equals(Files.readAllBytes(files.get(1)), ico)
                    && Files.isRegularFile(files.get(2)) && Arrays.equals(Files.readAllBytes(files.get(2)), made);
            writeIfChanged(files.get(1), ico);
            if (same && Files.isRegularFile(files.get(0))) return;
            Files.createDirectories(folder);
            ProcessBuilder shortcut = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:BMK_LNK); "
                            + "$s.TargetPath = $env:BMK_TARGET; $s.Arguments = $env:BMK_ARGS; "
                            + "$s.IconLocation = $env:BMK_ICON; $s.Description = $env:BMK_TITLE; $s.Save()");
            // The values cross as environment variables, never spliced into the script.
            Map<String, String> env = shortcut.environment();
            env.put("BMK_LNK", files.get(0).toString());
            env.put("BMK_TARGET", launcher);
            env.put("BMK_ARGS", arguments(entry));
            env.put("BMK_ICON", files.get(1) + ",0");
            env.put("BMK_TITLE", "Run " + entry.title() + " with BotMaker");
            Process p = shortcut.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("PowerShell did not answer.");
                }
                if (p.exitValue() != 0) throw new IOException("PowerShell exited " + p.exitValue() + ".");
                writeIfChanged(files.get(2), made);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted.", e);
            }
        }

        /** The shortcut's arguments: {@code --run=…}, quoted when it holds a space. */
        static String arguments(Entry entry) {
            String arg = "--run=" + entry.run();
            return arg.contains(" ") ? "\"" + arg + "\"" : arg;
        }

        @Override
        public List<Path> existing() throws IOException {
            return listed(folder).stream().filter(p -> p.getFileName().toString().endsWith(".lnk")).toList();
        }
    }

    /** {@code ~/Applications/BotMaker bots/<title>.app}, a script that opens Studio, its {@code .icns} inside. */
    record Mac(Path folder, Path icons) implements ProgramEntries {

        @Override
        public List<Path> files(Entry entry, String fileName) {
            return List.of(folder.resolve(fileName + ".app"));
        }

        @Override
        public void write(Entry entry, String fileName) throws IOException {
            Path contents = files(entry, fileName).getFirst().resolve("Contents");
            writeIfChanged(contents.resolve("Info.plist"), infoPlist(entry).getBytes(StandardCharsets.UTF_8));
            writeIfChanged(contents.resolve("Resources").resolve("icon.icns"), icns(png(entry, BotIcon.SIZE)));
            Path script = contents.resolve("MacOS").resolve("run");
            writeIfChanged(script, script(entry).getBytes(StandardCharsets.UTF_8));
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        @Override
        public List<Path> existing() throws IOException {
            return listed(folder).stream().filter(p -> p.getFileName().toString().endsWith(".app")).toList();
        }

        /** The script the bundle runs: a new Studio, so one already open on a project is left as it is. */
        static String script(Entry entry) {
            String arg = "--run=" + entry.run();
            return "#!/bin/sh\nexec /usr/bin/open -n -b " + STUDIO_BUNDLE + " --args '"
                    + arg.replace("'", "'\\''") + "'\n";
        }

        static String infoPlist(Entry entry) {
            List<String> keys = new ArrayList<>(List.of(
                    "CFBundleExecutable", "run",
                    "CFBundleIconFile", "icon",
                    "CFBundleIdentifier", "com.botmaker.bot." + entry.id(),
                    "CFBundleName", entry.title(),
                    "CFBundlePackageType", "APPL",
                    "CFBundleInfoDictionaryVersion", "6.0"));
            StringBuilder plist = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                    """);
            for (int i = 0; i < keys.size(); i += 2) {
                plist.append("  <key>").append(keys.get(i)).append("</key><string>").append(xml(keys.get(i + 1)))
                        .append("</string>\n");
            }
            return plist.append("</dict>\n</plist>\n").toString();
        }

        private static String xml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
