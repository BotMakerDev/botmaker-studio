package com.botmaker.studio.config;

import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {

    @Test
    void noSandboxKeepsTheUsersFolders() {
        assertEquals(Optional.empty(), AppPaths.sandbox(key -> null, key -> null));
        assertEquals(Path.of("/home/u", "BotMakerProjects"), AppPaths.projectsRoot(Optional.empty(), "/home/u"));
    }

    @Test
    void theEnvironmentOrThePropertyNamesTheSandboxThePropertyFirst() {
        Map<String, String> env = Map.of(AppPaths.HOME_ENV, "/tmp/env-home");
        assertEquals(Optional.of(Path.of("/tmp/env-home")), AppPaths.sandbox(env::get, key -> null));
        assertEquals(Optional.of(Path.of("/tmp/prop-home")),
                AppPaths.sandbox(env::get, Map.of(AppPaths.HOME_PROPERTY, "/tmp/prop-home")::get));
    }

    @Test
    void aSandboxHoldsTheProjectsTheDataTheSettingsAndThePreferences() {
        Path home = Path.of("/tmp/sandbox");
        assertEquals(home.resolve("projects"), AppPaths.projectsRoot(Optional.of(home), "/home/u"));
        assertEquals(Map.of(UserDirs.DATA_PROPERTY, "/tmp/sandbox/data",
                UserDirs.CONFIG_PROPERTY, "/tmp/sandbox/config",
                "java.util.prefs.userRoot", "/tmp/sandbox/prefs"), AppPaths.properties(home));
    }

    @Test
    void theUserDirsFileNamesTheDownloadAndDesktopFolders() {
        Path home = Path.of("/home/u");
        String dirs = """
                # written by xdg-user-dirs-update
                XDG_DESKTOP_DIR="$HOME/Bureau"
                XDG_DOWNLOAD_DIR="/data/Téléchargements"
                XDG_MUSIC_DIR="$HOME"
                """;
        assertEquals(Optional.of(Path.of("/home/u/Bureau")), AppPaths.userDir(AppPaths.XDG_DESKTOP, dirs, home));
        assertEquals(Optional.of(Path.of("/data/Téléchargements")),
                AppPaths.userDir(AppPaths.XDG_DOWNLOAD, dirs, home));
        assertEquals(Optional.empty(), AppPaths.userDir("XDG_MUSIC_DIR", dirs, home), "the home itself is none");
        assertEquals(Optional.empty(), AppPaths.userDir("XDG_VIDEOS_DIR", dirs, home), "not listed");
    }

    @Test
    void aSaveIsOfferedInTheFirstFolderThatExistsElseTheHome(@TempDir Path home) throws Exception {
        Path desktop = Files.createDirectories(home.resolve("Desktop"));
        assertEquals(desktop, AppPaths.firstFolder(home, List.of(home.resolve("Downloads"), desktop)));
        Path downloads = Files.createDirectories(home.resolve("Downloads"));
        assertEquals(downloads, AppPaths.firstFolder(home, List.of(downloads, desktop)));
        assertEquals(home, AppPaths.firstFolder(home, List.of(home.resolve("gone"))));
    }
}
