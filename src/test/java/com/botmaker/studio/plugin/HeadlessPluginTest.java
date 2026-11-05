package com.botmaker.studio.plugin;

import com.botmaker.core.plugin.UiPort;
import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin asking for a window in a host with no user interface gets the no-UI answer, not an exception. */
class HeadlessPluginTest {

    @Test
    void aWindowRequestWithNoUiIsAnsweredNoUi() {
        StudioServices services = HostServices.forProject(null, UiPort.NONE);

        assertFalse(services.hasUi());
        assertSame(Theme.PLAIN, services.theme());
        Dialogs dialogs = services.dialogs();
        assertTrue(dialogs.ownerWindow().isEmpty());
        assertEquals(Dialogs.Choice.cancelled(false), dialogs.chooseFile("Pick", Path.of(""), "png"));
        assertEquals(Dialogs.Choice.cancelled(false), dialogs.chooseDirectory("Pick", Path.of("")));
        assertEquals(Dialogs.Choice.cancelled(false), dialogs.chooseProgram(Path.of("")));
        assertEquals(Dialogs.Choice.cancelled(false), dialogs.saveFile("Save", Path.of(""), "a.png", "png"));
    }

    @Test
    void anAttachedUiIsTheOneHandedOut() {
        try {
            HostServices.attach(null);
            StudioServices held = HostServices.forProject(null);
            assertFalse(held.hasUi(), "no UI attached is no UI");
            HostServices.attach(FxUi.FOCUSED);
            assertTrue(held.hasUi(), "services held for the project follow a UI attached after they were made");
            HostServices.attach(null);
            assertSame(Dialogs.NONE, held.dialogs(), "and one detached");
        } finally {
            HostServices.attach(null);
        }
    }
}
