package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.toolbar.EnabledWhen;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.plugin.PluginHost.PluginBar;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Navigate ▸ Find Action (2026-10-28): what it lists, what a filter finds, and what a press does. Headless:
 * the rules are static, the popup only draws them.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ActionFinderTest {

    private static final PluginCoordinate SDK = new PluginCoordinate("com.example", "sdk");

    private static ToolbarItem item(String id, String label, AtomicInteger presses) {
        return ToolbarItem.id(id).label(label).tooltip(label).onBar(10).onPress(() -> c -> presses.incrementAndGet());
    }

    private static List<String> texts(List<ActionFinder.Entry> entries) {
        return entries.stream().map(ActionFinder.Entry::text).toList();
    }

    private final AtomicInteger presses = new AtomicInteger();
    private final List<ToolbarItem> studio = List.of(item("settings", "⚙ Settings", presses),
            item("parameters", "🎚 Parameters", presses));
    private final PluginBar sdk = new PluginBar(SDK, "BotMaker SDK", Path.of("sdk.jar"),
            List.of(item("screens", "🗺 Screens", presses), item("pictures", "🖼 Manage Pictures", presses)));

    @Test
    void studio_buttons_come_first_then_each_plugins_named_by_its_owner() {
        assertEquals(List.of("⚙ Settings · Studio", "🎚 Parameters · Studio", "🗺 Screens · BotMaker SDK",
                "🖼 Manage Pictures · BotMaker SDK"), texts(ActionFinder.entries(studio, List.of(sdk))));
    }

    @Test
    void the_filter_matches_the_label_without_its_glyph_ignoring_case() {
        List<ActionFinder.Entry> all = ActionFinder.entries(studio, List.of(sdk));

        assertEquals(List.of("🗺 Screens · BotMaker SDK"), texts(ActionFinder.matching(all, "SCR")));
        assertEquals(List.of(), texts(ActionFinder.matching(all, "🗺")));
        assertEquals(all, ActionFinder.matching(all, "  "));
        assertEquals("Manage Pictures", ActionFinder.plain("🖼 Manage Pictures"));
    }

    @Test
    void pressing_a_plugin_action_selects_its_tab_then_runs_it() {
        List<PluginCoordinate> selected = new ArrayList<>();
        ActionFinder.Entry screens = ActionFinder.entries(studio, List.of(sdk)).get(2);

        assertTrue(ActionFinder.press(screens, null, true, false, selected::add));
        assertEquals(List.of(SDK), selected);
        assertEquals(1, presses.get());
    }

    @Test
    void pressing_a_studio_action_selects_no_tab() {
        List<PluginCoordinate> selected = new ArrayList<>();

        assertTrue(ActionFinder.press(ActionFinder.entries(studio, List.of()).getFirst(), null, true, false,
                selected::add));
        assertEquals(List.of(), selected);
        assertEquals(1, presses.get());
    }

    @Test
    void a_disabled_action_is_not_pressed_and_selects_nothing() {
        ToolbarItem stop = ToolbarItem.id("stop").label("⏹ Stop").tooltip("Stop").onBar(10)
                .enabledWhen(EnabledWhen.BOT_RUNNING).onPress(() -> c -> presses.incrementAndGet());
        PluginBar bar = new PluginBar(SDK, "BotMaker SDK", null, List.of(stop));
        List<PluginCoordinate> selected = new ArrayList<>();

        assertFalse(ActionFinder.press(ActionFinder.entries(List.of(), List.of(bar)).getFirst(), null, true, false,
                selected::add));
        assertEquals(List.of(), selected);
        assertEquals(0, presses.get());
    }

    @Test
    void a_label_that_throws_is_listed_by_its_id() {
        ToolbarItem broken = ToolbarItem.id("broken").label(() -> { throw new IllegalStateException(); })
                .tooltip("x").onBar(10).onPress(() -> c -> { });

        assertEquals(List.of("broken · Studio"), texts(ActionFinder.entries(List.of(broken), List.of())));
    }
}
