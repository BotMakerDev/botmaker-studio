package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.PublishedBotPlugins;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** New Project's locked plugin cards (2026-10-24): a bot's own, and whatever a plugin the project gets brings. */
class PluginLockTest {

    private static final Map<String, String> NAMES = Map.of("sdk", "BotMaker SDK", "basics", "Basics",
            "a", "Alpha", "b", "Beta", "c", "Gamma");
    private static final Map<String, List<String>> SDK_BRINGS_BASICS = Map.of("sdk", List.of("basics"));

    private static Map<String, PublishedBotPlugins.Pin> pins(String... ids) {
        Map<String, PublishedBotPlugins.Pin> pins = new LinkedHashMap<>();
        for (String id : ids) pins.put(id, new PublishedBotPlugins.Pin(id, "v1"));
        return pins;
    }

    private static Map<String, String> texts(Map<String, PluginLock> locks) {
        Map<String, String> texts = new LinkedHashMap<>();
        locks.forEach((id, lock) -> texts.put(id, lock.text()));
        return texts;
    }

    @Test
    void botOwnUsedBy() {
        assertEquals(Map.of("sdk", "used by Firestone"),
                texts(PluginLock.of("Firestone", pins("sdk"), Set.of(), Map.of(), NAMES)));
    }

    @Test
    void broughtByBotPlugin() {
        assertEquals(Map.of("sdk", "used by Firestone", "basics", "brought by BotMaker SDK"),
                texts(PluginLock.of("Firestone", pins("sdk"), Set.of(), SDK_BRINGS_BASICS, NAMES)));
    }

    @Test
    void broughtByTicked() {
        assertEquals(Map.of("basics", "brought by BotMaker SDK"),
                texts(PluginLock.of("", Map.of(), Set.of("sdk"), SDK_BRINGS_BASICS, NAMES)));
        assertEquals(Map.of(), PluginLock.of("", Map.of(), Set.of(), SDK_BRINGS_BASICS, NAMES));
    }

    @Test
    void locksFollowTheChain() {
        Map<String, List<String>> brings = Map.of("a", List.of("b"), "b", List.of("c"));

        assertEquals(Map.of("b", "brought by Alpha", "c", "brought by Beta"),
                texts(PluginLock.of("", Map.of(), Set.of("a"), brings, NAMES)));
    }

    /** Review Focus 2: Basics ticked, then the SDK that brings it: Basics turns locked. */
    @Test
    void aTickedPluginAnotherBringsIsLocked() {
        assertEquals(Map.of("basics", "brought by BotMaker SDK"),
                texts(PluginLock.of("", Map.of(), Set.of("basics", "sdk"), SDK_BRINGS_BASICS, NAMES)));
    }

    @Test
    void cycleStops() {
        Map<String, List<String>> brings = Map.of("a", List.of("b"), "b", List.of("a"));

        assertEquals(Map.of("b", "brought by Alpha"), texts(PluginLock.of("", Map.of(), Set.of("a"), brings, NAMES)));
        assertEquals(Map.of("b", "brought by Alpha"),
                texts(PluginLock.of("", Map.of(), new LinkedHashSet<>(List.of("a", "b")), brings, NAMES)),
                "both ticked and bringing each other: the first ticked stays a pick");
        assertEquals(Map.of("a", "brought by Beta"),
                texts(PluginLock.of("", Map.of(), new LinkedHashSet<>(List.of("b", "a")), brings, NAMES)));
    }

    /** Review: a bot's own plugin that a ticked one brings back still locks the ticked one. */
    @Test
    void aCycleThroughTheBotsOwnLocksTheTicked() {
        Map<String, List<String>> brings = Map.of("a", List.of("b"), "b", List.of("a"));

        assertEquals(Map.of("a", "used by Firestone", "b", "brought by Alpha"),
                texts(PluginLock.of("Firestone", pins("a"), Set.of("b"), brings, NAMES)));
    }

    @Test
    void anUnnamedBringerIsNamedByItsId() {
        assertEquals(Map.of("basics", "brought by extra"),
                texts(PluginLock.of("", Map.of(), Set.of("extra"), Map.of("extra", List.of("basics")), NAMES)));
    }
}
