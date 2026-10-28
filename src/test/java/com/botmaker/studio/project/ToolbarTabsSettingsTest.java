package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The toolbar's plugin tabs and the overlay's Actions switch (2026-10-28) as {@code settings.json} keeps them. */
class ToolbarTabsSettingsTest {

    @TempDir
    Path project;

    @Test
    void theTabsAndTheSwitchSurviveARoundTripAndAnUnrelatedChange() throws Exception {
        Path studio = project.resolve(ProjectConfig.STUDIO_DIR);
        StudioProjectSettings.empty()
                .withToolbarTabs(List.of("com.example:zebra", "com.github.BotMakerDev:botmaker-sdk"))
                .withOverlayActionsHidden(true)
                .write(studio);

        StudioProjectSettings read = StudioProjectSettings.read(studio).withDevMode(true);

        assertEquals(List.of("com.example:zebra", "com.github.BotMakerDev:botmaker-sdk"), read.toolbarTabs());
        assertTrue(read.overlayActionsHidden());
        assertFalse(read.withOverlayActionsHidden(false).overlayActionsHidden());
    }

    /** The group names an older Studio wrote are ignored, not read as tabs: no legacy reader (2026-10-28). */
    @Test
    void aFileWithTheOldHiddenGroupsReadsAsNoTabs() throws Exception {
        Path studio = Files.createDirectories(project.resolve(ProjectConfig.STUDIO_DIR));
        Files.writeString(studio.resolve(StudioProjectSettings.FILE_NAME),
                "{\"hiddenToolbarGroups\":[\"OVERLAY\",\"TOOLS\"]}");

        StudioProjectSettings read = StudioProjectSettings.read(studio);

        assertEquals(List.of(), read.toolbarTabs());
        assertFalse(read.overlayActionsHidden());
    }
}
