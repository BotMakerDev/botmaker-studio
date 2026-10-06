package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dev mode (2026-10-06) as {@code settings.json} keeps it: off unless a project turned it on. */
class DevModeSettingsTest {

    @TempDir
    Path project;

    @Test
    void aProjectWithNoSettingsFileIsNotInDevMode() {
        assertFalse(StudioProjectSettings.devModeIn(project));
    }

    @Test
    void aFileWrittenBeforeDevModeExistedReadsAsOff() throws Exception {
        Path studio = Files.createDirectories(project.resolve(ProjectConfig.STUDIO_DIR));
        Files.writeString(studio.resolve(StudioProjectSettings.FILE_NAME), "{\"knownWindowTitles\":[\"Game\"]}");

        assertFalse(StudioProjectSettings.devModeIn(project));
        assertEquals(List.of("Game"), StudioProjectSettings.read(studio).knownWindowTitles());
    }

    @Test
    void itSurvivesARoundTripAndAnUnrelatedChange() throws Exception {
        StudioProjectSettings on = StudioProjectSettings.empty().withDevMode(true).withLastTarget("com.bot.Main#run");
        on.write(project.resolve(ProjectConfig.STUDIO_DIR));

        assertTrue(StudioProjectSettings.devModeIn(project));
        assertFalse(on.withDevMode(false).devMode());
    }
}
