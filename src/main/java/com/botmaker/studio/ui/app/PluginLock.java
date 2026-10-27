package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.PublishedBotPlugins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Why New Project shows a plugin card ticked and locked (2026-10-24): the picked bot's pom already declares it, or
 * a plugin the project gets brings it as an ordinary Maven dependency. Either way the project has it with nothing
 * to tick, so the card says who gives it and the plugin is no extra pick.
 */
record PluginLock(Reason reason, String by) {

    enum Reason {
        USED_BY("used-by", "used by"),
        BROUGHT_BY("brought-by", "brought by");

        private final String id;
        private final String displayName;

        Reason(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }
    }

    /** The card's line: "used by Firestone", "brought by BotMaker SDK". */
    String text() {
        return reason.displayName() + " " + by;
    }

    /**
     * The locked plugins by id, each its catalog entry's repo (2026-10-27). The bot's own are {@code used by botName}; then everything the bot's own and the
     * {@code ticked} ones bring, followed through {@code brings} breadth-first, bot-own before ticked, each in the
     * order given, is {@code brought by} the plugin whose {@code brings} reached it first ({@code names}, else its
     * id). An id keeps its first lock, and a ticked plugin is locked when another brings it, so the project never
     * declares a plugin beside one that brings it. Two ticked plugins that bring each other lock the later one
     * only; the walk stops at an id it has seen. Pure.
     */
    static Map<String, PluginLock> of(String botName, Map<String, PublishedBotPlugins.Pin> botOwn, Set<String> ticked,
                                      Map<String, List<String>> brings, Map<String, String> names) {
        Map<String, PluginLock> locks = new LinkedHashMap<>();
        botOwn.keySet().forEach(id -> locks.put(id, new PluginLock(Reason.USED_BY, botName)));
        List<String> roots = new ArrayList<>(new LinkedHashSet<>(botOwn.keySet()));
        ticked.stream().filter(id -> !roots.contains(id)).forEach(roots::add);
        for (String root : roots) {
            walk(root, brings).forEach((id, bringer) -> {
                if (locks.containsKey(id)) return;
                // Two ticked plugins that bring each other: the first ticked stays a pick, the other is locked.
                if (!botOwn.containsKey(root) && roots.indexOf(id) >= 0 && roots.indexOf(id) < roots.indexOf(root)
                        && walk(id, brings).containsKey(root)) return;
                locks.put(id, new PluginLock(Reason.BROUGHT_BY, names.getOrDefault(bringer, bringer)));
            });
        }
        return locks;
    }

    /** Every id {@code from} brings, near ones first, each with the plugin that brought it; {@code from} left out. */
    private static Map<String, String> walk(String from, Map<String, List<String>> brings) {
        Map<String, String> bringers = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>(List.of(from));
        Deque<String> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            for (String id : new ArrayList<>(brings.getOrDefault(next, List.of()))) {
                if (!seen.add(id)) continue;
                bringers.put(id, next);
                queue.add(id);
            }
        }
        return bringers;
    }
}
