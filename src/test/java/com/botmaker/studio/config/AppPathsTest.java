package com.botmaker.studio.config;

import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
}
