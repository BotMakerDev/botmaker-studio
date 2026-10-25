package com.botmaker.studio.config;

import com.botmaker.shared.tools.UserDirs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
