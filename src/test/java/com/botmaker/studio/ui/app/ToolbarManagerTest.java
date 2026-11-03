package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.toolbar.EnabledWhen;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Studio's own row of the toolbar. */
class ToolbarManagerTest {

    @Test
    void theStudioRowHasReports() {
        ToolbarManager toolbar = new ToolbarManager(new EventBus(false), null);
        AtomicInteger shown = new AtomicInteger();
        toolbar.setOnReports(shown::incrementAndGet);

        List<ToolbarItem> items = toolbar.studioItems();
        ToolbarItem reports = items.stream().filter(i -> i.id().equals("reports")).findFirst().orElseThrow();

        assertEquals("📊 Reports", reports.label().get());
        assertEquals(EnabledWhen.PROJECT_OPEN, reports.enabledWhen());
        assertEquals(List.of("settings", "parameters", "reports"), items.stream().limit(3).map(ToolbarItem::id).toList());
        reports.onClick().accept(null);
        assertEquals(1, shown.get());
    }
}
