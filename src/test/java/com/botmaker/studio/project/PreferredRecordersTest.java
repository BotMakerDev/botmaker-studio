package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which plugin records each gesture, as the HUD's <i>Record with</i> menu saves it: it survives a round trip
 * through {@code settings.json}, survives unrelated edits, and a settings file older than it still loads.
 */
class PreferredRecordersTest {

    @Test
    void aChoiceSurvivesARoundTrip(@TempDir Path dir) throws Exception {
        StudioProjectSettings.empty().withPreferredRecorder("CLICK", "com.example.other").write(dir);

        StudioProjectSettings read = StudioProjectSettings.read(dir);

        assertEquals("com.example.other", read.preferredRecorderFor("CLICK"));
        assertNull(read.preferredRecorderFor("TYPE"));
    }

    @Test
    void choosingAutomaticClearsTheChoice() {
        StudioProjectSettings settings = StudioProjectSettings.empty()
                .withPreferredRecorder("CLICK", "com.example.other")
                .withPreferredRecorder("CLICK", null);

        assertNull(settings.preferredRecorderFor("CLICK"));
    }

    @Test
    void anUnrelatedSettingsChangeKeepsTheChoice() {
        StudioProjectSettings after = StudioProjectSettings.empty()
                .withPreferredRecorder("DRAG", "com.example.other")
                .withPreferredEditor("java.awt.Rectangle", "com.example.geometry")
                .withLastRecordedActivity("Mining");

        assertEquals("com.example.other", after.preferredRecorderFor("DRAG"));
        assertEquals("com.example.geometry", after.preferredEditorFor("java.awt.Rectangle"));
    }

    @Test
    void settingsWrittenBeforeTheChoiceExistedStillLoad(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), "{\"preferredEditors\":{}}");

        assertNull(StudioProjectSettings.read(dir).preferredRecorderFor("CLICK"));
    }
}
