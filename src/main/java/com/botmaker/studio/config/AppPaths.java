package com.botmaker.studio.config;

import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where Studio keeps the user's bots and what it remembers of them, or a sandbox in their place (2026-10-25).
 *
 * <p>A Studio started with {@code BOTMAKER_HOME} (or {@code -Dbotmaker.home}) set to a folder keeps everything a
 * test would change under it: the projects ({@code projects/}, for {@code ~/BotMakerProjects} and its
 * {@code botmaker-config.json}), the bots opened from a file and the runners' values ({@code data/}), the settings
 * ({@code config/}), the Java preferences ({@code prefs/}) and the programs-menu entries (the folder stands for the
 * home they are made under). It is how Claude tries the bot list in a dev Studio without touching the user's own
 * bots. The cache stays the real one: it holds the GitHub login and the MCP endpoint's {@code mcp.json}, which the
 * bridge reads to reach this Studio, and nothing in it is the user's work.
 */
public final class AppPaths {

    /** The sandbox's environment variable; the bridge's {@code start_studio home} sets it. */
    public static final String HOME_ENV = "BOTMAKER_HOME";
    /** The same, as a system property. */
    public static final String HOME_PROPERTY = "botmaker.home";
    /** Java's own preferences root on Linux and macOS. */
    static final String PREFS_PROPERTY = "java.util.prefs.userRoot";

    private AppPaths() {}

    /** The sandbox folder, when one was asked for. */
    public static Optional<Path> sandbox() {
        return sandbox(System::getenv, System::getProperty);
    }

    static Optional<Path> sandbox(UnaryOperator<String> env, UnaryOperator<String> properties) {
        String set = properties.apply(HOME_PROPERTY);
        if (set == null || set.isBlank()) set = env.apply(HOME_ENV);
        return set == null || set.isBlank() ? Optional.empty() : Optional.of(Path.of(set).toAbsolutePath().normalize());
    }

    /** Where the projects are: {@code ~/BotMakerProjects}, or the sandbox's {@code projects/}. */
    public static Path projectsRoot() {
        return projectsRoot(sandbox(), System.getProperty("user.home"));
    }

    static Path projectsRoot(Optional<Path> sandbox, String userHome) {
        return sandbox.map(home -> home.resolve("projects"))
                .orElseGet(() -> Path.of(userHome, "BotMakerProjects").toAbsolutePath());
    }

    /** The home the programs-menu entries are made under: the user's, or the sandbox. */
    public static Path entriesHome() {
        return sandbox().orElseGet(() -> Path.of(System.getProperty("user.home")));
    }

    /**
     * The folder a save is offered in first (Export .bmk, 2026-10-25): the XDG download folder, else
     * {@code ~/Downloads}, else the desktop, else the home; the first that is a folder. Never the project's folder:
     * a file there is swept into the bot's next version (finding N3).
     */
    public static Path saveFolder() {
        Path home = entriesHome();
        return firstFolder(home, List.of(userDir(XDG_DOWNLOAD, home).orElse(home.resolve("Downloads")),
                home.resolve("Downloads"), desktop()));
    }

    /** The user's desktop folder: the XDG desktop folder, else {@code ~/Desktop}; the sandbox's {@code Desktop/}. */
    public static Path desktop() {
        Path home = entriesHome();
        return userDir(XDG_DESKTOP, home).orElse(home.resolve("Desktop"));
    }

    static final String XDG_DOWNLOAD = "XDG_DOWNLOAD_DIR";
    static final String XDG_DESKTOP = "XDG_DESKTOP_DIR";

    /** {@code key}'s folder in this user's {@code user-dirs.dirs}; never in a sandbox, which stands for the home. */
    private static Optional<Path> userDir(String key, Path home) {
        if (sandbox().isPresent()) return Optional.empty();
        String config = System.getenv("XDG_CONFIG_HOME");
        Path file = (config == null || config.isBlank() ? home.resolve(".config") : Path.of(config))
                .resolve("user-dirs.dirs");
        try {
            return userDir(key, Files.readString(file), home);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code key}'s folder in {@code text}, a {@code user-dirs.dirs} ({@code XDG_DOWNLOAD_DIR="$HOME/Téléchargements"}):
     * a path under {@code $HOME} or an absolute one, as {@code xdg-user-dirs} writes them. A folder set to the home
     * itself means none (the spec's way to turn one off).
     */
    static Optional<Path> userDir(String key, String text, Path home) {
        for (String line : text.lines().map(String::strip).toList()) {
            if (!line.startsWith(key + "=")) continue;
            String value = line.substring(key.length() + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            Path dir;
            if (value.equals("$HOME") || value.equals("$HOME/")) return Optional.empty();
            else if (value.startsWith("$HOME/")) dir = home.resolve(value.substring("$HOME/".length()));
            else if (value.startsWith("/")) dir = Path.of(value);
            else return Optional.empty();
            return Optional.of(dir.normalize());
        }
        return Optional.empty();
    }

    /** The first of {@code candidates} that is a folder, else {@code home}. */
    static Path firstFolder(Path home, List<Path> candidates) {
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(home);
    }

    /** The properties a sandbox sets, so {@link UserDirs} and Java's preferences answer inside it. */
    static Map<String, String> properties(Path sandbox) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(UserDirs.DATA_PROPERTY, sandbox.resolve("data").toString());
        out.put(UserDirs.CONFIG_PROPERTY, sandbox.resolve("config").toString());
        out.put(PREFS_PROPERTY, sandbox.resolve("prefs").toString());
        return out;
    }

    /**
     * Points the per-user folders into the sandbox, when there is one; a property already given on the command line
     * is kept. Called before anything reads a folder: Java's preferences read their root once.
     */
    public static void apply() {
        sandbox().ifPresent(home -> {
            properties(home).forEach((key, value) -> {
                if (System.getProperty(key) == null) System.setProperty(key, value);
            });
            System.out.println("Sandbox home: " + home);
        });
    }
}
