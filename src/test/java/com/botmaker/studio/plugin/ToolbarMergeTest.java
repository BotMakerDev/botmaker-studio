package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How plugins' toolbar items compose: one tab per plugin on the bar (2026-10-28), the overlay's items apart.
 *
 * <p>Every rule here is one <b>with no visible symptom when it is wrong</b>, which is why it is tested rather
 * than left to the eye: a bar in a slightly different order reads as somebody's preference, and a button
 * silently absent reads as a feature that was never written. They are the order, the tie-break that makes it
 * independent of discovery order, a plugin failing alone, and the split between the bar and the overlay.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ToolbarMergeTest {

    /** A plugin that offers exactly what it is handed. */
    private static LoadedPlugin fake(String artifactId, List<ToolbarItem> items) {
        return TestPlugins.loaded(artifactId, StudioPlugin.declare().toolbar(TestPlugins.holder(() -> items)));
    }

    /** A plugin whose item list throws — a bug in somebody else's jar, met while a project is opening. */
    private static LoadedPlugin broken(String artifactId) {
        return TestPlugins.loaded(artifactId, StudioPlugin.declare().toolbar(TestPlugins.holder(() -> {
            throw new IllegalStateException("no");
        })));
    }

    private static ToolbarItem bar(String id, int order) {
        return ToolbarItem.id(id).label(id).tooltip(id).onBar(order).onPress(() -> c -> { });
    }

    private static ToolbarItem overlay(String id, int order) {
        return ToolbarItem.id(id).label(id).tooltip(id).inOverlay(order).onPress(() -> c -> { });
    }

    private static List<String> ids(List<ToolbarItem> items) {
        List<String> out = new ArrayList<>();
        for (ToolbarItem item : items) out.add(item.id());
        return out;
    }

    @Test
    void a_plugins_items_sort_by_their_own_order() {
        List<ToolbarItem> merged = PluginHost.mergeToolbarItems(List.of(
                fake("a", List.of(bar("late", 90), bar("early", 10), bar("middle", 20)))));

        assertEquals(List.of("early", "middle", "late"), ids(merged));
    }

    /**
     * Two plugins are ordered by name, then by coordinate, never by which one the loader happened to find
     * first — the same reasoning that makes the value catalog refuse a clash rather than let load order
     * decide it.
     */
    @Test
    void plugins_order_by_name_rather_than_by_discovery_order() {
        List<ToolbarItem> zebraFirst = PluginHost.mergeToolbarItems(List.of(
                fake("zebra", List.of(bar("z", 10))),
                fake("alpha", List.of(bar("a", 10)))));
        List<ToolbarItem> alphaFirst = PluginHost.mergeToolbarItems(List.of(
                fake("alpha", List.of(bar("a", 10))),
                fake("zebra", List.of(bar("z", 10)))));

        assertEquals(List.of("a", "z"), ids(zebraFirst));
        assertEquals(ids(alphaFirst), ids(zebraFirst), "the bar must not depend on which plugin loaded first");
    }

    /** A plugin that cannot list its buttons costs its own and nothing else — never the project. */
    @Test
    void a_plugin_that_throws_does_not_cost_the_others_their_items() {
        List<ToolbarItem> merged = PluginHost.mergeToolbarItems(List.of(
                broken("broken"),
                fake("good", List.of(bar("kept", 1)))));

        assertEquals(List.of("kept"), ids(merged));
    }

    /** A plugin contributing nothing is the ordinary case, and the default the contract ships. */
    @Test
    void a_plugin_that_contributes_nothing_is_not_an_error() {
        assertTrue(PluginHost.mergeToolbarItems(List.of(fake("a", List.of()))).isEmpty());
        assertTrue(PluginHost.mergeToolbarItems(List.of(fake("a", null))).isEmpty());
        assertTrue(PluginHost.mergeToolbarItems(List.of()).isEmpty());
    }

    /**
     * With no project bound the bar carries no plugin items at all: Studio bundles no plugin, and nothing is
     * loaded until a project is.
     */
    @Test
    void an_unbound_host_contributes_no_plugin_items() {
        assertTrue(PluginHost.plugins().isEmpty(), "Studio bundles no plugin: " + PluginHost.plugins());
        assertTrue(PluginHost.toolbarItems().isEmpty(),
                "the bar carries plugin items with nothing loaded: " + ids(PluginHost.toolbarItems()));
        assertTrue(PluginHost.barItemsByPlugin().isEmpty());
    }

    /**
     * Each plugin with bar items gets one tab, in name order, holding its bar items only and knowing its jar.
     * Tested because an item the grouping loses is a button silently absent.
     */
    @Test
    void bar_items_group_into_one_tab_per_plugin() {
        List<PluginHost.OwnedItem> owned = PluginHost.mergeOwnedToolbarItems(List.of(
                fake("zebra", List.of(bar("z2", 20), bar("z1", 10))),
                fake("alpha", List.of(bar("a", 10), overlay("point", 10)))));

        List<PluginHost.PluginBar> bars = PluginHost.barItemsByPlugin(owned);

        assertEquals(List.of(TestPlugins.coordinate("alpha"), TestPlugins.coordinate("zebra")),
                bars.stream().map(PluginHost.PluginBar::plugin).toList());
        assertEquals(List.of("a"), ids(bars.get(0).items()));
        assertEquals(List.of("z1", "z2"), ids(bars.get(1).items()));
        assertEquals("alpha", bars.getFirst().name());
        assertEquals(owned.getFirst().jar(), bars.getFirst().jar());
    }

    /** A plugin whose only items are the overlay's has nothing on the bar, so it gets no tab. */
    @Test
    void a_plugin_with_overlay_items_only_has_no_tab() {
        List<PluginHost.OwnedItem> owned = PluginHost.mergeOwnedToolbarItems(List.of(
                fake("overlay-only", List.of(overlay("point", 10))),
                fake("bar", List.of(bar("open", 10)))));

        assertEquals(List.of(TestPlugins.coordinate("bar")),
                PluginHost.barItemsByPlugin(owned).stream().map(PluginHost.PluginBar::plugin).toList());
    }

    /** One filter, two readers — the bar takes the bar's items, the overlay editor takes only its own. */
    @Test
    void overlay_items_answers_the_overlays_items_only() {
        List<ToolbarItem> merged = PluginHost.mergeToolbarItems(List.of(fake("com.example.one", List.of(
                overlay("point-here", 10),
                bar("cut", 20)))));

        assertEquals(List.of("point-here"), ids(PluginHost.overlayItems(merged)));
    }
}
