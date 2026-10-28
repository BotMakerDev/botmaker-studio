package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.plugin.PluginHost.PluginBar;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The plugin row's tabs (2026-10-28): which plugins have one, in what order, how many fit, and what the
 * {@code » N more} list finds. Headless: the rules are static, the node only draws them.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class PluginTabsTest {

    private static PluginBar bar(String artifactId, String name) {
        ToolbarItem item = ToolbarItem.id(artifactId).label(name).tooltip(name).onBar(10).onPress(() -> c -> { });
        return new PluginBar(new PluginCoordinate("com.example", artifactId), name, Path.of(artifactId + ".jar"),
                List.of(item));
    }

    private static final PluginBar ALPHA = bar("alpha", "Alpha");
    private static final PluginBar SDK = bar("sdk", "BotMaker SDK");
    private static final PluginBar ZEBRA = bar("zebra", "Zebra tools");
    private static final List<PluginBar> BY_NAME = List.of(ALPHA, SDK, ZEBRA);

    private static List<String> names(List<PluginBar> bars) {
        return bars.stream().map(PluginBar::name).toList();
    }

    @Test
    void with_nothing_remembered_the_tabs_keep_the_name_order() {
        assertEquals(List.of("Alpha", "BotMaker SDK", "Zebra tools"), names(PluginTabs.ordered(BY_NAME, List.of())));
    }

    @Test
    void the_most_recent_tabs_come_first_and_the_first_is_selected() {
        List<PluginBar> ordered = PluginTabs.ordered(BY_NAME, List.of("com.example:zebra", "com.example:sdk"));

        assertEquals(List.of("Zebra tools", "BotMaker SDK", "Alpha"), names(ordered));
    }

    @Test
    void a_remembered_plugin_that_is_gone_is_skipped_and_dropped_on_the_next_pick() {
        List<PluginBar> ordered = PluginTabs.ordered(BY_NAME, List.of("com.example:removed", "com.example:sdk"));

        assertEquals(List.of("BotMaker SDK", "Alpha", "Zebra tools"), names(ordered));
        assertEquals(List.of("com.example:zebra", "com.example:sdk", "com.example:alpha"),
                PluginTabs.picked(ordered, ZEBRA.plugin()));
    }

    @Test
    void picking_the_selected_tab_again_changes_nothing() {
        List<PluginBar> ordered = PluginTabs.ordered(BY_NAME, List.of("com.example:sdk"));

        assertEquals(List.of("com.example:sdk", "com.example:alpha", "com.example:zebra"),
                PluginTabs.picked(ordered, SDK.plugin()));
    }

    @Test
    void the_tabs_that_do_not_fit_go_behind_the_more_button() {
        double[] widths = {100, 100, 100, 100};

        assertEquals(4, PluginTabs.visibleCount(widths, 415, 60), "everything fits, no » button");
        assertEquals(2, PluginTabs.visibleCount(widths, 300, 60), "two tabs and the » button");
        assertEquals("» 2 more", PluginTabs.moreLabel(4 - 2));
    }

    @Test
    void the_selected_tab_is_drawn_even_when_nothing_fits() {
        assertEquals(1, PluginTabs.visibleCount(new double[]{200, 100}, 50, 60));
        assertEquals(0, PluginTabs.visibleCount(new double[]{}, 50, 60));
    }

    @Test
    void the_more_list_filters_by_name_or_coordinate_ignoring_case() {
        assertEquals(List.of("BotMaker SDK"), names(PluginTabs.matching(BY_NAME, "sdk")));
        assertEquals(List.of("Zebra tools"), names(PluginTabs.matching(BY_NAME, "TOOLS")));
        assertEquals(List.of("Alpha"), names(PluginTabs.matching(BY_NAME, "example:al")));
        assertEquals(BY_NAME, PluginTabs.matching(BY_NAME, " "));
    }
}
