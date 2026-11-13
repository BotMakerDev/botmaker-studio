package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.toolbar.EnabledWhen;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.studio.services.ProjectSettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Studio's own row of the toolbar. */
class ToolbarManagerTest {

    @Test
    void theStudioRowHasReportsAndVersions() {
        ToolbarManager toolbar = new ToolbarManager(new EventBus(false), null);
        AtomicInteger reportsShown = new AtomicInteger();
        AtomicInteger versionsShown = new AtomicInteger();
        toolbar.setOnReports(reportsShown::incrementAndGet);
        toolbar.setOnVersions(versionsShown::incrementAndGet);

        List<ToolbarItem> items = toolbar.studioItems();
        ToolbarItem reports = items.stream().filter(i -> i.id().equals("reports")).findFirst().orElseThrow();
        ToolbarItem versions = items.stream().filter(i -> i.id().equals("versions")).findFirst().orElseThrow();

        assertEquals("▤ Reports", reports.label().get());
        assertEquals("⟲ Versions", versions.label().get());
        assertEquals(EnabledWhen.PROJECT_OPEN, reports.enabledWhen());
        assertEquals(EnabledWhen.PROJECT_OPEN, versions.enabledWhen());
        assertEquals(List.of("parameters", "reports", "versions"),
                items.stream().limit(3).map(ToolbarItem::id).toList());
        reports.onClick().accept(null);
        versions.onClick().accept(null);
        assertEquals(1, reportsShown.get());
        assertEquals(1, versionsShown.get());
    }

    /** Project Settings went on 2026-11-13, and the overlay editor shows only in a dev-mode project. */
    @Test
    void neitherSettingsNorTheOverlayOutsideDevMode() {
        List<String> ids = new ToolbarManager(new EventBus(false), null).studioItems().stream()
                .map(ToolbarItem::id).toList();

        assertFalse(ids.contains("settings"), ids.toString());
        assertFalse(ids.contains("overlay"), ids.toString());
    }

    @Test
    void theOverlayShowsInADevModeProject() {
        ProjectState state = new ProjectState();
        state.setSettings(StudioProjectSettings.empty().withDevMode(true));
        EventBus bus = new EventBus(false);
        List<String> ids = new ToolbarManager(bus, new ProjectSettingsService(null, state, bus)).studioItems()
                .stream().map(ToolbarItem::id).toList();

        assertTrue(ids.contains("overlay"), ids.toString());
        assertFalse(ids.contains("settings"), ids.toString());
    }
}
